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
package com.facebook.presto.operator;

import com.facebook.presto.operator.PartitionedOutputOperator.PartitionedOutputInfo;
import com.facebook.presto.sql.planner.plan.PlanNodeId;
import com.google.common.collect.ImmutableList;
import io.airlift.json.JsonCodec;
import io.airlift.units.DataSize;
import io.airlift.units.Duration;
import org.testng.annotations.Test;

import java.util.Optional;

import static io.airlift.units.DataSize.Unit.BYTE;
import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static org.testng.Assert.assertEquals;

public class TestOperatorStats
{
    private static final ExchangeClientStatus NON_MERGEABLE_INFO = new ExchangeClientStatus(0, 1, 2, false, ImmutableList.of());
    private static final PartitionedOutputInfo MERGEABLE_INFO = new PartitionedOutputInfo(1, 2);

    public static final OperatorStats EXPECTED = new OperatorStats(
            41,
            new PlanNodeId("test"),
            "test",

            1,
            new Duration(2, NANOSECONDS),
            new Duration(3, NANOSECONDS),
            new Duration(4, NANOSECONDS),
            new DataSize(5, BYTE),
            6,

            7,
            new Duration(8, NANOSECONDS),
            new Duration(9, NANOSECONDS),
            new Duration(10, NANOSECONDS),
            new DataSize(11, BYTE),
            12,

            new Duration(13, NANOSECONDS),

            14,
            new Duration(15, NANOSECONDS),
            new Duration(16, NANOSECONDS),
            new Duration(17, NANOSECONDS),

            new DataSize(18, BYTE),
            new DataSize(19, BYTE),
            Optional.empty(),
            NON_MERGEABLE_INFO);

    public static final OperatorStats MERGEABLE = new OperatorStats(
            41,
            new PlanNodeId("test"),
            "test",

            1,
            new Duration(2, NANOSECONDS),
            new Duration(3, NANOSECONDS),
            new Duration(4, NANOSECONDS),
            new DataSize(5, BYTE),
            6,

            7,
            new Duration(8, NANOSECONDS),
            new Duration(9, NANOSECONDS),
            new Duration(10, NANOSECONDS),
            new DataSize(11, BYTE),
            12,

            new Duration(13, NANOSECONDS),

            14,
            new Duration(15, NANOSECONDS),
            new Duration(16, NANOSECONDS),
            new Duration(17, NANOSECONDS),

            new DataSize(18, BYTE),
            new DataSize(19, BYTE),
            Optional.empty(),
            MERGEABLE_INFO);

    @Test
    public void testJson()
    {
        JsonCodec<OperatorStats> codec = JsonCodec.jsonCodec(OperatorStats.class);

        String json = codec.toJson(EXPECTED);
        OperatorStats actual = codec.fromJson(json);

        assertExpectedOperatorStats(actual);
    }

    public static void assertExpectedOperatorStats(OperatorStats actual)
    {
        assertEquals(actual.getOperatorId(), 41);
        assertEquals(actual.getOperatorType(), "test");

        assertEquals(actual.getAddInputCalls(), 1);
        assertEquals(actual.getAddInputWall(), new Duration(2, NANOSECONDS));
        assertEquals(actual.getAddInputCpu(), new Duration(3, NANOSECONDS));
        assertEquals(actual.getAddInputUser(), new Duration(4, NANOSECONDS));
        assertEquals(actual.getInputDataSize(), new DataSize(5, BYTE));
        assertEquals(actual.getInputPositions(), 6);

        assertEquals(actual.getGetOutputCalls(), 7);
        assertEquals(actual.getGetOutputWall(), new Duration(8, NANOSECONDS));
        assertEquals(actual.getGetOutputCpu(), new Duration(9, NANOSECONDS));
        assertEquals(actual.getGetOutputUser(), new Duration(10, NANOSECONDS));
        assertEquals(actual.getOutputDataSize(), new DataSize(11, BYTE));
        assertEquals(actual.getOutputPositions(), 12);

        assertEquals(actual.getBlockedWall(), new Duration(13, NANOSECONDS));

        assertEquals(actual.getFinishCalls(), 14);
        assertEquals(actual.getFinishWall(), new Duration(15, NANOSECONDS));
        assertEquals(actual.getFinishCpu(), new Duration(16, NANOSECONDS));
        assertEquals(actual.getFinishUser(), new Duration(17, NANOSECONDS));

        assertEquals(actual.getMemoryReservation(), new DataSize(18, BYTE));
        assertEquals(actual.getSystemMemoryReservation(), new DataSize(19, BYTE));
        assertEquals(actual.getInfo().getClass(), ExchangeClientStatus.class);
        assertEquals(((ExchangeClientStatus) actual.getInfo()).getAverageBytesPerRequest(), NON_MERGEABLE_INFO.getAverageBytesPerRequest());
    }

    @Test
    public void testAdd()
    {
        OperatorStats actual = EXPECTED.add(EXPECTED, EXPECTED);

        assertEquals(actual.getOperatorId(), 41);
        assertEquals(actual.getOperatorType(), "test");

        assertEquals(actual.getAddInputCalls(), 3 * 1);
        assertEquals(actual.getAddInputWall(), new Duration(3 * 2, NANOSECONDS));
        assertEquals(actual.getAddInputCpu(), new Duration(3 * 3, NANOSECONDS));
        assertEquals(actual.getAddInputUser(), new Duration(3 * 4, NANOSECONDS));
        assertEquals(actual.getInputDataSize(), new DataSize(3 * 5, BYTE));
        assertEquals(actual.getInputPositions(), 3 * 6);

        assertEquals(actual.getGetOutputCalls(), 3 * 7);
        assertEquals(actual.getGetOutputWall(), new Duration(3 * 8, NANOSECONDS));
        assertEquals(actual.getGetOutputCpu(), new Duration(3 * 9, NANOSECONDS));
        assertEquals(actual.getGetOutputUser(), new Duration(3 * 10, NANOSECONDS));
        assertEquals(actual.getOutputDataSize(), new DataSize(3 * 11, BYTE));
        assertEquals(actual.getOutputPositions(), 3 * 12);

        assertEquals(actual.getBlockedWall(), new Duration(3 * 13, NANOSECONDS));

        assertEquals(actual.getFinishCalls(), 3 * 14);
        assertEquals(actual.getFinishWall(), new Duration(3 * 15, NANOSECONDS));
        assertEquals(actual.getFinishCpu(), new Duration(3 * 16, NANOSECONDS));
        assertEquals(actual.getFinishUser(), new Duration(3 * 17, NANOSECONDS));
        assertEquals(actual.getMemoryReservation(), new DataSize(3 * 18, BYTE));
        assertEquals(actual.getSystemMemoryReservation(), new DataSize(3 * 19, BYTE));
        assertEquals(actual.getInfo(), null);
    }

    @Test
    public void testAddMergeable()
    {
        OperatorStats actual = MERGEABLE.add(MERGEABLE, MERGEABLE);

        assertEquals(actual.getOperatorId(), 41);
        assertEquals(actual.getOperatorType(), "test");

        assertEquals(actual.getAddInputCalls(), 3 * 1);
        assertEquals(actual.getAddInputWall(), new Duration(3 * 2, NANOSECONDS));
        assertEquals(actual.getAddInputCpu(), new Duration(3 * 3, NANOSECONDS));
        assertEquals(actual.getAddInputUser(), new Duration(3 * 4, NANOSECONDS));
        assertEquals(actual.getInputDataSize(), new DataSize(3 * 5, BYTE));
        assertEquals(actual.getInputPositions(), 3 * 6);

        assertEquals(actual.getGetOutputCalls(), 3 * 7);
        assertEquals(actual.getGetOutputWall(), new Duration(3 * 8, NANOSECONDS));
        assertEquals(actual.getGetOutputCpu(), new Duration(3 * 9, NANOSECONDS));
        assertEquals(actual.getGetOutputUser(), new Duration(3 * 10, NANOSECONDS));
        assertEquals(actual.getOutputDataSize(), new DataSize(3 * 11, BYTE));
        assertEquals(actual.getOutputPositions(), 3 * 12);

        assertEquals(actual.getBlockedWall(), new Duration(3 * 13, NANOSECONDS));

        assertEquals(actual.getFinishCalls(), 3 * 14);
        assertEquals(actual.getFinishWall(), new Duration(3 * 15, NANOSECONDS));
        assertEquals(actual.getFinishCpu(), new Duration(3 * 16, NANOSECONDS));
        assertEquals(actual.getFinishUser(), new Duration(3 * 17, NANOSECONDS));
        assertEquals(actual.getMemoryReservation(), new DataSize(3 * 18, BYTE));
        assertEquals(actual.getSystemMemoryReservation(), new DataSize(3 * 19, BYTE));
        assertEquals(actual.getInfo().getClass(), PartitionedOutputInfo.class);
        assertEquals(((PartitionedOutputInfo) actual.getInfo()).getPagesAdded(), 3 * MERGEABLE_INFO.getPagesAdded());
    }
}
