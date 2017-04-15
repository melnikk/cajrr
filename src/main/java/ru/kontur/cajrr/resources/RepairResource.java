package ru.kontur.cajrr.resources;

import com.ecwid.consul.v1.ConsulClient;
import com.ecwid.consul.v1.Response;
import com.ecwid.consul.v1.kv.model.GetValue;
import org.apache.cassandra.utils.concurrent.SimpleCondition;
import org.codehaus.jackson.map.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.kontur.cajrr.AppConfiguration;
import ru.kontur.cajrr.api.*;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

@Path("/repair")
@Produces(MediaType.APPLICATION_JSON)
public class RepairResource {

    private static final Logger LOG = LoggerFactory.getLogger(RepairResource.class);
    private static ObjectMapper mapper = new ObjectMapper();
    private final AppConfiguration config;
    private final ConsulClient consul;
    public TableResource tableResource;
    public RingResource ringResource;
    private AtomicBoolean needToRepair = new AtomicBoolean(true);
    private boolean error;
    private String statKey;

    public RepairResource(AppConfiguration config) {
        this.config = config;
        this.consul = new ConsulClient(config.consul);
    }

    public void run() {

        while (needToRepair.get()) {
            RepairStats stats = readStats();


            stats.cluster = config.cluster;
            stats.clusterTotal = totalCluster(config.keyspaces);
            int completed = stats.clearIfCompleted();
            int count = 0;

            for (String keyspace : config.keyspaces) {
                stats.keyspace = keyspace;
                stats.keyspaceTotal = totalKeyspace(keyspace);

                List<Table> tables = tableResource.getTables(keyspace);
                for (Table table : tables) {
                    stats.table = table.name;
                    stats.tableTotal = totalTable(keyspace, table);


                    List<Token> tokens = ringResource.describe(keyspace, table.getSlices());
                    for (Token token : tokens) {
                        List<Fragment> ranges = token.getRanges();

                        for (Fragment frag : ranges) {
                            if (count >= completed) {
                                Repair repair = new Repair();
                                repair.started = Instant.now();

                                repair.id = frag.id;
                                repair.cluster = config.cluster;
                                repair.keyspace = keyspace;
                                repair.table = table.name;
                                repair.endpoint = frag.endpoint;
                                repair.start = frag.getStart();
                                repair.end = frag.getEnd();

                                SimpleCondition condition = new SimpleCondition();
                                Duration elapsed = registerRepair(repair, condition);
                                if (error) {
                                    stats = stats.errorRepair(repair, elapsed);
                                } else {
                                    stats = stats.completeRepair(repair, elapsed);
                                }
                                LOG.info(stats.toString());

                                saveStats(stats);
                            }
                            count++;
                            if(!needToRepair.get()) break;
                        }
                        if(!needToRepair.get()) break;
                    }
                    if(!needToRepair.get()) break;
                    stats.clearTable();
                }
                if(!needToRepair.get()) break;
                stats.clearKeyspace();
            }
            try {
                Thread.sleep(config.interval);
                stats.clearCluster();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }

    private RepairStats readStats() {
        RepairStats result = new RepairStats();
        Response<GetValue> keyValueResponse = consul.getKVValue(statKey);
        GetValue json = keyValueResponse.getValue();
        if (json != null) {
            result.loadFromJson(json.getDecodedValue());
        }

        result.host = config.serviceHost;
        return result;
    }

    private void saveStats(RepairStats stats) {
        try {
            String jsonString = mapper.writeValueAsString(stats);
            consul.setKVValue(statKey, jsonString);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }


    private int totalKeyspace(String keyspace) {
        int result = 0;
        List<Table> tables = tableResource.getTables(keyspace);

        for (Table table : tables) {
            result += totalTable(keyspace, table);
        }
        return result;
    }

    private int totalTable(String keyspace, Table table) {
        int result = 0;
        List<Token> tokens = ringResource.describe(keyspace, table.getSlices());

        for (Token token : tokens) {
            List<Fragment> ranges = token.getRanges();
            result += ranges.size();
        }
        return result;
    }

    private int totalCluster(List<String> keyspaces) {
        int result = 0;
        for (String keyspace: keyspaces) {
            result += totalKeyspace(keyspace);
        }
        return result;
    }

    @GET
    @Path("/stop")
    public String stop() {
        needToRepair.set(false);
        return "OK";
    }


    private Duration registerRepair(Repair repair, SimpleCondition condition) {
        LOG.info("Starting fragment: " + repair.toString());
        Instant start = repair.started;
        try {
            Node proxy = config.findNode(repair.getProxyNode());
            repair.setCondition(condition);
            proxy.addListener(repair);
            repair.command = proxy.repairAsync(repair.keyspace, repair.getOptions());

            if (repair.command <= 0)
            {
                LOG.warn(String.format("There is nothing to repair in keyspace %s", repair.keyspace));
            }
            else
            {
                condition.await();
                this.error = false;
            }

            proxy.removeListener(repair);

        }   catch (Exception e) {
            this.error = true;
            e.printStackTrace();
        }
        Instant end = Instant.now();
        return Duration.between(start, end);
    }

    public void setStatKey(String statKey) {
        this.statKey = statKey;
    }
}
