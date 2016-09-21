/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ru.kontur.cajrr.tools;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.Lists;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.kontur.cajrr.api.RepairRange;

import java.math.BigInteger;
import java.util.List;

/**
 * Splits given Cassandra table's (column family's) token range into RepairSegments.
 */
public class SegmentGenerator {

    private static final Logger LOG = LoggerFactory.getLogger(SegmentGenerator.class);

    private final BigInteger RANGE_MAX;
    private final BigInteger RANGE_SIZE;

    public SegmentGenerator(String partitioner) throws Exception {
        BigInteger RANGE_MIN;
        if (partitioner.endsWith("RandomPartitioner")) {
            RANGE_MIN = BigInteger.ZERO;
            RANGE_MAX = new BigInteger("2").pow(127).subtract(BigInteger.ONE);
        } else if (partitioner.endsWith("Murmur3Partitioner")) {
            RANGE_MIN = new BigInteger("2").pow(63).negate();
            RANGE_MAX = new BigInteger("2").pow(63).subtract(BigInteger.ONE);
        } else {
            throw new Exception("Unsupported partitioner " + partitioner);
        }
        RANGE_SIZE = RANGE_MAX.subtract(RANGE_MIN).add(BigInteger.ONE);
    }

    @VisibleForTesting
    public static BigInteger max(BigInteger a, BigInteger b) {
        return greaterThan(a, b) ? a : b;
    }

    @VisibleForTesting
    public static BigInteger min(BigInteger a, BigInteger b) {
        return lowerThan(a, b) ? a : b;
    }

    @VisibleForTesting
    public static boolean lowerThan(BigInteger a, BigInteger b) {
        return a.compareTo(b) < 0;
    }

    @VisibleForTesting
    public static boolean lowerThanOrEqual(BigInteger a, BigInteger b) {
        return a.compareTo(b) <= 0;
    }

    @VisibleForTesting
    public static boolean greaterThan(BigInteger a, BigInteger b) {
        return a.compareTo(b) > 0;
    }

    @VisibleForTesting
    public static boolean greaterThanOrEqual(BigInteger a, BigInteger b) {
        return a.compareTo(b) >= 0;
    }

    /**
     * Given a properly ordered list of tokens, compute at least {@code totalSegmentCount} repair
     * segments.
     *
     * @param totalSegmentCount requested total amount of repair segments. This function may generate
     *                          more segments.
     * @param ringTokens        list of all start tokens in a cluster. They have to be in ring order.
     * @return a list containing at least {@code totalSegmentCount} repair segments.
     */
    public List<RepairRange> generateSegments(Long totalSegmentCount, List<BigInteger> ringTokens)
            throws Exception {
        int tokenRangeCount = ringTokens.size();

        List<RepairRange> repairSegments = Lists.newArrayList();
        for (int i = 0; i < tokenRangeCount; i++) {
            BigInteger start = ringTokens.get(i);
            BigInteger stop = ringTokens.get((i + 1) % tokenRangeCount);

            BigInteger rangeSize = stop.subtract(start);
            if (lowerThanOrEqual(rangeSize, BigInteger.ZERO)) {
                // wrap around case
                rangeSize = rangeSize.add(RANGE_SIZE);
            }

            // the below, in essence, does this:
            // segmentCount = ceiling((rangeSize / RANGE_SIZE) * totalSegmentCount)
            BigInteger[] segmentCountAndRemainder =
                    rangeSize.multiply(BigInteger.valueOf(totalSegmentCount)).divideAndRemainder(rangeSize);
            int segmentCount = segmentCountAndRemainder[0].intValue() +
                    (segmentCountAndRemainder[1].equals(BigInteger.ZERO) ? 0 : 1);

            LOG.info("Dividing token range [{},{}) into {} segments", start, stop, segmentCount);

            // Make a list of all the endpoints for the repair segments, including both start and stop
            List<BigInteger> endpointTokens = Lists.newArrayList();
            for (int j = 0; j <= segmentCount; j++) {
                BigInteger offset = rangeSize
                        .multiply(BigInteger.valueOf(j))
                        .divide(BigInteger.valueOf(segmentCount));
                BigInteger nextToken = start.add(offset);
                if (greaterThan(nextToken, RANGE_MAX)) {
                    nextToken = nextToken.subtract(RANGE_SIZE);
                }
                endpointTokens.add(nextToken);
            }

            // Append the segments between the endpoints
            for (int j = 0; j < segmentCount; j++) {
                repairSegments.add(new RepairRange(endpointTokens.get(j), endpointTokens.get(j + 1)));
                LOG.debug("Segment #{}: [{},{})", j + 1, endpointTokens.get(j),
                        endpointTokens.get(j + 1));
            }
        }

        // verify that the whole range is repaired
        BigInteger total = BigInteger.ZERO;
        for (RepairRange segment : repairSegments) {
            BigInteger size = segment.span(RANGE_SIZE);
            total = total.add(size);
        }

        return repairSegments;
    }

}
