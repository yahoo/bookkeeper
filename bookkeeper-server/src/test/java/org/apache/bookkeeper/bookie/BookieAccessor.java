/**
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 *
 */
package org.apache.bookkeeper.bookie;

import java.io.IOException;
import java.util.concurrent.Future;

import org.apache.bookkeeper.bookie.CheckpointSource.Checkpoint;

/**
 * Accessor class to avoid making Bookie internals public
 */
public class BookieAccessor {
    /**
     * Force a bookie to flush its ledger storage
     */
    public static void forceFlush(Bookie b) throws IOException {
        CheckpointSourceList source = new CheckpointSourceList(b.journals);
        Checkpoint checkpoint = source.newCheckpoint();

        b.ledgerStorage.flush();

        source.checkpointComplete(checkpoint, true);
    }

    public static Future<?> triggerGC(Bookie b) {
        InterleavedLedgerStorage storage = ((InterleavedLedgerStorage)b.ledgerStorage);
        storage.gcThread.enableForceGC();
        return storage.gcThread.triggerGC();
    }

    public static boolean ledgerExists(Bookie b, long ledgerId) throws IOException {
        return b.ledgerStorage.ledgerExists(ledgerId);
    }
}
