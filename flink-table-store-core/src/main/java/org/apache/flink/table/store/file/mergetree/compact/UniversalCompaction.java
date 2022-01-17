/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.table.store.file.mergetree.compact;

import org.apache.flink.annotation.VisibleForTesting;
import org.apache.flink.table.store.file.mergetree.LevelSortedRun;
import org.apache.flink.table.store.file.mergetree.SortedRun;

import java.util.List;
import java.util.Optional;

/**
 * Universal Compaction Style is a compaction style, targeting the use cases requiring lower write
 * amplification, trading off read amplification and space amplification.
 *
 * <p>See RocksDb Universal-Compaction:
 * https://github.com/facebook/rocksdb/wiki/Universal-Compaction.
 */
public class UniversalCompaction implements CompactStrategy {

    private final int maxSizeAmp;
    private final int sizeRatio;
    private final int maxRunNum;

    public UniversalCompaction(int maxSizeAmp, int sizeRatio, int maxRunNum) {
        this.maxSizeAmp = maxSizeAmp;
        this.sizeRatio = sizeRatio;
        this.maxRunNum = maxRunNum;
    }

    @Override
    public Optional<CompactUnit> pick(int numLevels, List<LevelSortedRun> runs) {
        int maxLevel = numLevels - 1;

        // 1 checking for reducing size amplification
        CompactUnit unit = pickForSizeAmp(maxLevel, runs);
        if (unit != null) {
            return Optional.of(unit);
        }

        // 2 checking for size ratio
        unit = pickForSizeRatio(maxLevel, runs);
        if (unit != null) {
            return Optional.of(unit);
        }

        // 3 checking for file num
        if (runs.size() > maxRunNum) {
            // compacting for file num
            return Optional.of(createUnit(runs, maxLevel, runs.size() - maxRunNum + 1));
        }

        return Optional.empty();
    }

    @VisibleForTesting
    CompactUnit pickForSizeAmp(int maxLevel, List<LevelSortedRun> runs) {
        if (runs.size() < maxRunNum) {
            return null;
        }

        long candidateSize =
                runs.subList(0, runs.size() - 1).stream()
                        .map(LevelSortedRun::run)
                        .mapToLong(SortedRun::totalSize)
                        .sum();

        long earliestRunSize = runs.get(runs.size() - 1).run().totalSize();

        // size amplification = percentage of additional size
        if (candidateSize * 100 > maxSizeAmp * earliestRunSize) {
            return CompactUnit.fromLevelRuns(maxLevel, runs);
        }

        return null;
    }

    @VisibleForTesting
    CompactUnit pickForSizeRatio(int maxLevel, List<LevelSortedRun> runs) {
        if (runs.size() < maxRunNum) {
            return null;
        }

        int candidateCount = 1;
        long candidateSize = runs.get(0).run().totalSize();

        for (int i = 1; i < runs.size(); i++) {
            LevelSortedRun next = runs.get(i);
            if (candidateSize * (100.0 + sizeRatio) / 100.0 < next.run().totalSize()) {
                break;
            }

            candidateSize += next.run().totalSize();
            candidateCount++;
        }

        if (candidateCount > 1) {
            return createUnit(runs, maxLevel, candidateCount);
        }

        return null;
    }

    @VisibleForTesting
    static CompactUnit createUnit(List<LevelSortedRun> runs, int maxLevel, int runCount) {
        int outputLevel;
        if (runCount == runs.size()) {
            outputLevel = maxLevel;
        } else {
            outputLevel = Math.max(0, runs.get(runCount).level() - 1);
        }

        return CompactUnit.fromLevelRuns(outputLevel, runs.subList(0, runCount));
    }
}