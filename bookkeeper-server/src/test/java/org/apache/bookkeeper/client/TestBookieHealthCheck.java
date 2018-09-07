package org.apache.bookkeeper.client;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.apache.bookkeeper.client.AsyncCallback.AddCallback;
import org.apache.bookkeeper.net.BookieSocketAddress;
import org.apache.bookkeeper.test.BookKeeperClusterTestCase;
import org.junit.Assert;
import org.junit.Test;
import org.junit.Ignore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TestBookieHealthCheck extends BookKeeperClusterTestCase {
    private final static Logger LOG = LoggerFactory.getLogger(TestBookieHealthCheck.class);

    public TestBookieHealthCheck() {
        super(4);
        baseClientConf.setAddEntryTimeout(1);
        baseClientConf.enableBookieHealthCheck();
        baseClientConf.setBookieHealthCheckInterval(1, TimeUnit.SECONDS);
        baseClientConf.setBookieErrorThresholdPerInterval(1);
        baseClientConf.setBookieQuarantineTime(5, TimeUnit.SECONDS);
    }

    @Test
    public void testBkQuarantine() throws Exception {
        LedgerHandle lh = bkc.createLedger(2, 2, 2, BookKeeper.DigestType.CRC32, new byte[] {});

        final int numEntries = 10;
        for (int i = 0; i < numEntries; i++) {
            byte[] msg = ("msg-" + i).getBytes();
            lh.addEntry(msg);
        }

        BookieSocketAddress bookieToQuarantine = lh.getLedgerMetadata().currentEnsemble.get(0);

        CountDownLatch addEntryLatch = new CountDownLatch(1);
        sleepBookie(bookieToQuarantine, addEntryLatch).await();

        byte[] tempMsg = "temp-msg".getBytes();

        // Wait for bookie to completely suspend processing
        Thread.sleep(2000);
        lh.asyncAddEntry(tempMsg, new AddCallback() {

            @Override
            public void addComplete(int rc, LedgerHandle lh, long entryId, Object ctx) {
                addEntryLatch.countDown();
            }
        }, null);

        // make sure the health check runs once after the timeout
        Thread.sleep(baseClientConf.getBookieHealthCheckIntervalSeconds() * 2 * 1000);

        // the bookie watcher should contain the bookieToQuarantine in the quarantine set
        Assert.assertTrue(bkc.bookieWatcher.quarantinedBookies.asMap().containsKey(bookieToQuarantine));

        // the bookie to be left out of the ensemble should always be the quarantined bookie
        LedgerHandle lh1 = bkc.createLedger(2, 2, 2, BookKeeper.DigestType.CRC32, new byte[] {});
        LedgerHandle lh2 = bkc.createLedger(3, 3, 3, BookKeeper.DigestType.CRC32, new byte[] {});
        Assert.assertFalse(lh1.getLedgerMetadata().getEnsemble(0).contains(bookieToQuarantine));
        Assert.assertFalse(lh2.getLedgerMetadata().getEnsemble(0).contains(bookieToQuarantine));

        // the quarantined bookie can still be in the ensemble if we do not have enough healthy bookies
        LedgerHandle lh3 = bkc.createLedger(4, 4, 4, BookKeeper.DigestType.CRC32, new byte[] {});
        Assert.assertTrue(lh3.getLedgerMetadata().getEnsemble(0).contains(bookieToQuarantine));

        // make sure faulty bookie is out of quarantine
        Thread.sleep(baseClientConf.getBookieQuarantineTimeSeconds() * 1000);

        // the bookie should not be quarantined anymore
        Assert.assertFalse(bkc.bookieWatcher.quarantinedBookies.asMap().containsKey(bookieToQuarantine));
    }

    @Test
    public void testNoQuarantineOnBkRestart() throws Exception {
        final LedgerHandle lh = bkc.createLedger(2, 2, 2, BookKeeper.DigestType.CRC32, new byte[] {});
        final int numEntries = 20;
        BookieSocketAddress bookieToRestart = lh.getLedgerMetadata().getEnsemble(0).get(0);

        // we add entries on a separate thread so that we can restart a bookie on the current thread
        Thread addEntryThread = new Thread() {
            public void run() {
                for (int i = 0; i < numEntries; i++) {
                    byte[] msg = ("msg-" + i).getBytes();
                    try {
                        lh.addEntry(msg);
                        // we add sufficient sleep to make sure all entries are not added before we restart the bookie
                        Thread.sleep(100);
                    } catch (Exception e) {
                        LOG.error("Error sending msg");
                    }
                }
            }
        };
        addEntryThread.start();
        restartBookie(bookieToRestart);

        // make sure the health check runs once
        Thread.sleep(baseClientConf.getBookieHealthCheckIntervalSeconds() * 2 * 1000);

        // the bookie watcher should not contain the bookieToRestart in the quarantine set
        Assert.assertFalse(bkc.bookieWatcher.quarantinedBookies.asMap().containsKey(bookieToRestart));
    }

    @Test
    public void testNoQuarantineOnExpectedBkErrors() throws Exception {
        final LedgerHandle lh = bkc.createLedger(2, 2, 2, BookKeeper.DigestType.CRC32, new byte[] {});
        final int numEntries = 10;
        for (int i = 0; i < numEntries; i++) {
            byte[] msg = ("msg-" + i).getBytes();
            lh.addEntry(msg);
        }
        BookieSocketAddress bookie1 = lh.getLedgerMetadata().getEnsemble(0).get(0);
        BookieSocketAddress bookie2 = lh.getLedgerMetadata().getEnsemble(0).get(1);
        try {
            // we read an entry that is not added
            lh.readEntries(10, 10);
        } catch (BKException e) {
            // ok
        }

        // make sure the health check runs once
        Thread.sleep(baseClientConf.getBookieHealthCheckIntervalSeconds() * 2 * 1000);

        // the bookie watcher should not contain the bookieToRestart in the quarantine set
        Assert.assertFalse(bkc.bookieWatcher.quarantinedBookies.asMap().containsKey(bookie1));
        Assert.assertFalse(bkc.bookieWatcher.quarantinedBookies.asMap().containsKey(bookie2));
    }

}