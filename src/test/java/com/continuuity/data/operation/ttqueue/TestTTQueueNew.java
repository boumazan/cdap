package com.continuuity.data.operation.ttqueue;

import com.continuuity.api.data.OperationException;
import com.continuuity.common.conf.CConfiguration;
import com.continuuity.common.io.BinaryDecoder;
import com.continuuity.common.io.BinaryEncoder;
import com.continuuity.data.operation.StatusCode;
import com.continuuity.data.operation.executor.ReadPointer;
import com.continuuity.data.operation.executor.Transaction;
import com.continuuity.data.operation.ttqueue.TTQueueNewOnVCTable.DequeueEntry;
import com.continuuity.data.operation.ttqueue.TTQueueNewOnVCTable.DequeuedEntrySet;
import com.continuuity.data.operation.ttqueue.TTQueueNewOnVCTable.TransientWorkingSet;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import org.apache.hadoop.hbase.util.Bytes;
import org.junit.Ignore;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.StringWriter;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;

import static com.continuuity.data.operation.ttqueue.QueuePartitioner.PartitionerType;
import static com.continuuity.data.operation.ttqueue.QueuePartitioner.PartitionerType.FIFO;
import static com.continuuity.data.operation.ttqueue.QueuePartitioner.PartitionerType.HASH;
import static com.continuuity.data.operation.ttqueue.QueuePartitioner.PartitionerType.ROUND_ROBIN;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 *
 */
public abstract class TestTTQueueNew extends TestTTQueue {

  private static final int MAX_CRASH_DEQUEUE_TRIES = 10;

  protected void updateCConfiguration(CConfiguration conf) {
    // Setting evict interval to be high -ve number of seconds for testing,
    // so that evictions can be asserted immediately in tests.
    conf.setLong(TTQueueNewOnVCTable.TTQUEUE_EVICT_INTERVAL_SECS, Long.MIN_VALUE);
    conf.setInt(TTQueueNewOnVCTable.TTQUEUE_MAX_CRASH_DEQUEUE_TRIES, MAX_CRASH_DEQUEUE_TRIES);
  }

  // Test DequeueEntry
  @Test
  public void testDequeueEntryEncode() throws Exception {
    DequeueEntry expectedEntry = new DequeueEntry(1, 2);

    ByteArrayOutputStream bos = new ByteArrayOutputStream();
    expectedEntry.encode(new BinaryEncoder(bos));
    byte[] encodedValue = bos.toByteArray();

    DequeueEntry actualEntry = DequeueEntry.decode(new BinaryDecoder(new ByteArrayInputStream(encodedValue)));
    assertEquals(expectedEntry.getEntryId(), actualEntry.getEntryId());
    assertEquals(expectedEntry.getTries(), actualEntry.getTries());
    assertEquals(expectedEntry, actualEntry);
  }

  @Test
  public void testDequeueEntryEquals() throws Exception {
    // DequeueEntry is only compared using entryId, tries is ignored
    assertEquals(new DequeueEntry(12, 10), new DequeueEntry(12));
    assertEquals(new DequeueEntry(12, 10), new DequeueEntry(12, 10));
    assertEquals(new DequeueEntry(12, 10), new DequeueEntry(12, 15));

    assertNotEquals(new DequeueEntry(12, 10), new DequeueEntry(13, 10));
    assertNotEquals(new DequeueEntry(12, 10), new DequeueEntry(13, 11));
  }

  @Test
  public void testDequeueEntryCompare() throws Exception {
    // DequeueEntry is compared only on entryId, tries is ignored
    SortedSet<DequeueEntry> sortedSet = new TreeSet<DequeueEntry>();
    sortedSet.add(new DequeueEntry(5, 1));
    sortedSet.add(new DequeueEntry(2, 3));
    sortedSet.add(new DequeueEntry(1, 2));
    sortedSet.add(new DequeueEntry(3, 2));
    sortedSet.add(new DequeueEntry(4, 3));
    sortedSet.add(new DequeueEntry(4, 5));
    sortedSet.add(new DequeueEntry(0, 2));

    int expected = 0;
    for (DequeueEntry aSortedSet : sortedSet) {
      assertEquals(expected++, aSortedSet.getEntryId());
    }
  }

  @Test
  public void testQueueEntrySetEncode() throws Exception {
    final int MAX = 10;
    DequeuedEntrySet expectedEntrySet = new DequeuedEntrySet();
    for(int i = 0; i < MAX; ++i) {
      expectedEntrySet.add(new DequeueEntry(i, i % 2));
    }

    assertEquals(MAX, expectedEntrySet.size());

    ByteArrayOutputStream bos = new ByteArrayOutputStream();
    expectedEntrySet.encode(new BinaryEncoder(bos));
    byte[] encodedValue = bos.toByteArray();

    DequeuedEntrySet actualEntrySet = DequeuedEntrySet.decode(new BinaryDecoder(new ByteArrayInputStream(encodedValue)));
    assertEquals(expectedEntrySet.size(), actualEntrySet.size());
    for(int i = 0; i < MAX; ++i) {
      DequeueEntry expectedEntry = expectedEntrySet.min();
      expectedEntrySet.remove(expectedEntry.getEntryId());

      DequeueEntry actualEntry = actualEntrySet.min();
      actualEntrySet.remove(actualEntry.getEntryId());

      assertEquals(expectedEntry.getEntryId(), actualEntry.getEntryId());
      assertEquals(expectedEntry.getTries(), actualEntry.getTries());
    }
  }

  @Test
  public void testDequeueEntrySet() throws Exception {
    final int MAX = 10;
    DequeuedEntrySet entrySet = new DequeuedEntrySet();
    List<Long> expectedEntryIds = Lists.newArrayListWithCapacity(MAX);
    List<DequeueEntry> expectedEntryList = Lists.newArrayListWithCapacity(MAX);
    Set<Long> expectedDroppedEntries = Sets.newHashSetWithExpectedSize(MAX);

    for(int i = 0; i < MAX; ++i) {
      entrySet.add(new DequeueEntry(i, i %2));
      expectedEntryIds.add((long) i);
      expectedEntryList.add(new DequeueEntry(i, i % 2));

      if(i % 2 == 1) {
        expectedDroppedEntries.add((long) i);
      }
    }

    // Verify created lists
    assertEquals(MAX, entrySet.size());
    assertEquals(MAX, expectedEntryIds.size());
    assertEquals(MAX, expectedEntryList.size());
    assertEquals(MAX/2, expectedDroppedEntries.size());

    // Verify QueueEntrySet
    assertEquals(expectedEntryIds, entrySet.getEntryIds());
    assertEquals(expectedEntryList, entrySet.getEntryList());

    Set<Long> actualDroppedEntries = entrySet.startNewTry(1);
    assertEquals(expectedDroppedEntries, actualDroppedEntries);

    List<Long> actualRemainingEntries = Lists.newArrayListWithCapacity(MAX);
    for(int i = 0; i < MAX; ++i) {
      if(i % 2 == 0) {
        actualRemainingEntries.add((long) i);
      }
    }
    assertEquals(actualRemainingEntries, entrySet.getEntryIds());
  }

  @Test
  public void testWorkingEntryList() {
    final int MAX = 10;
    Map<Long, byte[]> noCachedEntries = Collections.emptyMap();
    TTQueueNewOnVCTable.TransientWorkingSet transientWorkingSet =
      new TransientWorkingSet(Lists.<Long>newArrayList(), noCachedEntries);
    assertFalse(transientWorkingSet.hasNext());

    List<Long> workingSet = Lists.newArrayList();
    Map<Long, byte[]> cache = Maps.newHashMap();
    for(long i = 0; i < MAX; ++i) {
      workingSet.add(i);
      cache.put(i, Bytes.toBytes(i));
    }
    transientWorkingSet = new TransientWorkingSet(workingSet, cache);

    for(int i = 0; i < MAX; ++i) {
      assertTrue(transientWorkingSet.hasNext());
      assertEquals(new DequeueEntry(i), transientWorkingSet.peekNext());
      assertEquals(new DequeueEntry(i), transientWorkingSet.next());
    }
    assertFalse(transientWorkingSet.hasNext());
  }

  @Test
  public void testReconfigPartitionInstance() throws Exception {
    final TTQueueNewOnVCTable.ReconfigPartitionInstance reconfigPartitionInstance1 =
      new TTQueueNewOnVCTable.ReconfigPartitionInstance(3, 100L);

    final TTQueueNewOnVCTable.ReconfigPartitionInstance reconfigPartitionInstance2 =
      new TTQueueNewOnVCTable.ReconfigPartitionInstance(2, 100L);

    final TTQueueNewOnVCTable.ReconfigPartitionInstance reconfigPartitionInstance3 =
      new TTQueueNewOnVCTable.ReconfigPartitionInstance(2, 101L);

    final TTQueueNewOnVCTable.ReconfigPartitionInstance reconfigPartitionInstance4 =
      new TTQueueNewOnVCTable.ReconfigPartitionInstance(3, 100L);

    // Verify equals
    assertEquals(reconfigPartitionInstance1, reconfigPartitionInstance1);
    assertEquals(reconfigPartitionInstance1, reconfigPartitionInstance4);
    assertNotEquals(reconfigPartitionInstance1, reconfigPartitionInstance2);
    assertNotEquals(reconfigPartitionInstance2, reconfigPartitionInstance3);
    assertNotEquals(reconfigPartitionInstance4, reconfigPartitionInstance3);

    // Verify redundancy
    assertTrue(reconfigPartitionInstance1.isRedundant(1000L));
    assertTrue(reconfigPartitionInstance1.isRedundant(101L));
    assertFalse(reconfigPartitionInstance1.isRedundant(100L));
    assertFalse(reconfigPartitionInstance1.isRedundant(99L));
    assertFalse((reconfigPartitionInstance1.isRedundant(10L)));

    // Encode to bytes
    ByteArrayOutputStream bos = new ByteArrayOutputStream();
    reconfigPartitionInstance1.encode(new BinaryEncoder(bos));
    byte[] bytes = bos.toByteArray();

    // Decode from bytes
    TTQueueNewOnVCTable.ReconfigPartitionInstance actual =
      TTQueueNewOnVCTable.ReconfigPartitionInstance.decode(new BinaryDecoder(new ByteArrayInputStream(bytes)));

    // Verify
    assertEquals(reconfigPartitionInstance1, actual);
  }

  @Test
  public void testReconfigPartitioner() throws Exception {
    TTQueueNewOnVCTable.ReconfigPartitioner partitioner1 =
      new TTQueueNewOnVCTable.ReconfigPartitioner(3, PartitionerType.HASH);
    partitioner1.add(0, 5);
    partitioner1.add(1, 10);
    partitioner1.add(2, 12);

    TTQueueNewOnVCTable.ReconfigPartitioner partitioner2 =
      new TTQueueNewOnVCTable.ReconfigPartitioner(3, PartitionerType.HASH);
    partitioner2.add(0, 5);
    partitioner2.add(1, 15);
    partitioner2.add(2, 12);

    // Verify equals
    assertEquals(partitioner1, partitioner1);
    assertNotEquals(partitioner1, partitioner2);

    // Verify redundancy
    assertTrue(partitioner1.isRedundant(100L));
    assertTrue(partitioner1.isRedundant(54L));
    assertTrue(partitioner1.isRedundant(13L));
    for(int i = 0; i < 12; ++i) {
      assertFalse(partitioner1.isRedundant(i));
    }

    // Encode to bytes
    ByteArrayOutputStream bos = new ByteArrayOutputStream();
    partitioner1.encode(new BinaryEncoder(bos));
    byte[] bytes = bos.toByteArray();

    // Decode from bytes
    TTQueueNewOnVCTable.ReconfigPartitioner actual =
      TTQueueNewOnVCTable.ReconfigPartitioner.decode(new BinaryDecoder(new ByteArrayInputStream(bytes)));

    // Verify
    assertEquals(partitioner1, actual);
  }

  @Test
  public void testReconfigPartitionerEmit1() throws Exception {
    // Partition
    // Entries        :1  2  3  4  5  6  7  8  9  10  11  12
    // Consumers Ack  :      0           1  2
    // Partition      :1  2  0  1  2  0  1  2  0  1   2   0

    int groupSize = 3;
    int lastEntry = 12;

    long zeroAck = 3L;
    long oneAck = 7L;
    long twoAck = 8L;

    TTQueueNewOnVCTable.ReconfigPartitioner partitioner =
      new TTQueueNewOnVCTable.ReconfigPartitioner(groupSize, PartitionerType.ROUND_ROBIN);
    partitioner.add(0, zeroAck);
    partitioner.add(1, oneAck);
    partitioner.add(2, twoAck);

    verifyTestReconfigPartitionerEmit(lastEntry, ImmutableSet.of(1L, 2L, 3L, 4L, 5L, 7L, 8L), partitioner);
  }

  @Test
  public void testReconfigPartitionerEmit2() throws Exception {
    // Partition
    // Entries        :1  2  3  4  5  6  7  8  9  10  11  12
    // Consumers Ack  :1  2  0
    // Partition      :1  2  0  1  2  0  1  2  0  1   2   0

    int groupSize = 3;
    int lastEntry = 12;

    long zeroAck = 3L;
    long oneAck = 1L;
    long twoAck = 2L;

    TTQueueNewOnVCTable.ReconfigPartitioner partitioner =
      new TTQueueNewOnVCTable.ReconfigPartitioner(groupSize, PartitionerType.ROUND_ROBIN);
    partitioner.add(0, zeroAck);
    partitioner.add(1, oneAck);
    partitioner.add(2, twoAck);

    verifyTestReconfigPartitionerEmit(lastEntry, ImmutableSet.of(1L, 2L, 3L), partitioner);
  }

  @Test
  public void testReconfigPartitionerEmit3() throws Exception {
    // Partition
    // Entries        :1  2  3  4  5  6  7  8  9  10  11  12
    // Consumers Ack  :1  2                               0
    // Partition      :1  2  0  1  2  0  1  2  0  1   2   0

    int groupSize = 3;
    int lastEntry = 12;

    long zeroAck = 12L;
    long oneAck = 1L;
    long twoAck = 2L;

    TTQueueNewOnVCTable.ReconfigPartitioner partitioner =
      new TTQueueNewOnVCTable.ReconfigPartitioner(groupSize, PartitionerType.ROUND_ROBIN);
    partitioner.add(0, zeroAck);
    partitioner.add(1, oneAck);
    partitioner.add(2, twoAck);

    verifyTestReconfigPartitionerEmit(lastEntry, ImmutableSet.of(1L, 2L, 3L, 6L, 9L, 12L), partitioner);
  }

  @Test
  public void testReconfigPartitionerEmit4() throws Exception {
    // Partition
    // Entries        :1  2  3  4  5  6  7  8  9  10  11  12
    // Consumers Ack  :                           1   2   0
    // Partition      :1  2  0  1  2  0  1  2  0  1   2   0

    int groupSize = 3;
    int lastEntry = 12;

    long zeroAck = 12L;
    long oneAck = 10L;
    long twoAck = 11L;

    TTQueueNewOnVCTable.ReconfigPartitioner partitioner =
      new TTQueueNewOnVCTable.ReconfigPartitioner(groupSize, PartitionerType.ROUND_ROBIN);
    partitioner.add(0, zeroAck);
    partitioner.add(1, oneAck);
    partitioner.add(2, twoAck);

    verifyTestReconfigPartitionerEmit(lastEntry, ImmutableSet.of(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L, 11L, 12L),
                                      partitioner);
  }

  @Test
  public void testReconfigPartitionersList() throws Exception {
    List<TTQueueNewOnVCTable.ReconfigPartitioner> partitionerList = Lists.newArrayList();
    List<Long> partitionerListMaxAck = Lists.newArrayList();

    int groupSize = 3;

    TTQueueNewOnVCTable.ReconfigPartitioner partitioner =
      new TTQueueNewOnVCTable.ReconfigPartitioner(groupSize, PartitionerType.ROUND_ROBIN);
    partitioner.add(0, 8L);
    partitioner.add(1, 15L);
    partitioner.add(2, 11L);
    partitionerList.add(partitioner);
    partitionerListMaxAck.add(15L);

    // Verify compaction with one partitioner
    TTQueueNewOnVCTable.ReconfigPartitionersList rl1 =
      new TTQueueNewOnVCTable.ReconfigPartitionersList(partitionerList);
    assertEquals(1, rl1.getReconfigPartitioners().size());
    rl1.compact(9L);
    assertEquals(1, rl1.getReconfigPartitioners().size());
    rl1.compact(16L);
    assertTrue(rl1.getReconfigPartitioners().isEmpty());
    verifyReconfigPartitionersListCompact(
      new TTQueueNewOnVCTable.ReconfigPartitionersList(partitionerList), partitionerListMaxAck);

    groupSize = 4;

    partitioner =
      new TTQueueNewOnVCTable.ReconfigPartitioner(groupSize, PartitionerType.ROUND_ROBIN);
    partitioner.add(0, 30L);
    partitioner.add(1, 14L);
    partitioner.add(2, 15L);
    partitioner.add(3, 28L);
    partitionerList.add(partitioner);
    partitionerListMaxAck.add(30L);

    // Verify compaction with two partitioners
    TTQueueNewOnVCTable.ReconfigPartitionersList rl2 =
      new TTQueueNewOnVCTable.ReconfigPartitionersList(partitionerList);
    assertEquals(2, rl2.getReconfigPartitioners().size());
    rl2.compact(8L);
    assertEquals(2, rl2.getReconfigPartitioners().size());
    rl2.compact(16L);
    assertEquals(1, rl2.getReconfigPartitioners().size());
    rl2.compact(31L);
    assertTrue(rl2.getReconfigPartitioners().isEmpty());
    verifyReconfigPartitionersListCompact(
      new TTQueueNewOnVCTable.ReconfigPartitionersList(partitionerList), partitionerListMaxAck);

    groupSize = 5;

    partitioner =
      new TTQueueNewOnVCTable.ReconfigPartitioner(groupSize, PartitionerType.ROUND_ROBIN);
    partitioner.add(0, 43L);
    partitioner.add(1, 47L);
    partitioner.add(2, 3L);
    partitioner.add(3, 32L);
    partitioner.add(3, 35L);
    partitionerList.add(partitioner);
    partitionerListMaxAck.add(47L);

    // Verify compaction with 3 partitioners
    verifyReconfigPartitionersListCompact(
      new TTQueueNewOnVCTable.ReconfigPartitionersList(partitionerList), partitionerListMaxAck);

    groupSize = 2;

    partitioner =
      new TTQueueNewOnVCTable.ReconfigPartitioner(groupSize, PartitionerType.ROUND_ROBIN);
    partitioner.add(0, 55);
    partitioner.add(1, 50L);
    partitionerList.add(partitioner);
    partitionerListMaxAck.add(55L);

    // Verify compaction with 4 partitioners
    verifyReconfigPartitionersListCompact(
      new TTQueueNewOnVCTable.ReconfigPartitionersList(partitionerList), partitionerListMaxAck);

    // Verify encode/decode
    TTQueueNewOnVCTable.ReconfigPartitionersList expectedEncode =
      new TTQueueNewOnVCTable.ReconfigPartitionersList(partitionerList);
    ByteArrayOutputStream bos = new ByteArrayOutputStream();
    expectedEncode.encode(new BinaryEncoder(bos));
    byte[] bytes = bos.toByteArray();

    TTQueueNewOnVCTable.ReconfigPartitionersList actualEncode =
      TTQueueNewOnVCTable.ReconfigPartitionersList.decode(new BinaryDecoder(new ByteArrayInputStream(bytes)));
    assertEquals(expectedEncode, actualEncode);
  }

  private void verifyReconfigPartitionersListCompact(
    TTQueueNewOnVCTable.ReconfigPartitionersList reconfigPartitionersList, List<Long> maxAckList) {
    List<Long> sortedMaxAckList = Lists.newLinkedList(maxAckList);
    Collections.sort(sortedMaxAckList);

    long max = sortedMaxAckList.get(sortedMaxAckList.size() - 1);
    for(long i = 0; i <= max; ++i) {
      reconfigPartitionersList.compact(i);
      if(!sortedMaxAckList.isEmpty() && i > sortedMaxAckList.get(0)) {
        sortedMaxAckList.remove(0);
      }
      assertEquals(sortedMaxAckList.size(), reconfigPartitionersList.getReconfigPartitioners().size());
    }
    reconfigPartitionersList.compact(max + 1);
    assertTrue(reconfigPartitionersList.getReconfigPartitioners().isEmpty());
  }

  @Test
  public void testReconfigPartitionersListEmit1() throws Exception {
    List<TTQueueNewOnVCTable.ReconfigPartitioner> partitionerList = Lists.newArrayList();
    // Partition 1
    // Entries        :1  2  3  4  5  6  7  8  9  10  11  12
    // Consumers Ack  :      0           1  2
    // Partition      :1  2  0  1  2  0  1  2  0  1   2   0

    int groupSize = 3;

    long zeroAck = 3L;
    long oneAck = 7L;
    long twoAck = 8L;

    TTQueueNewOnVCTable.ReconfigPartitioner partitioner =
      new TTQueueNewOnVCTable.ReconfigPartitioner(groupSize, PartitionerType.ROUND_ROBIN);
    partitioner.add(0, zeroAck);
    partitioner.add(1, oneAck);
    partitioner.add(2, twoAck);

    partitionerList.add(partitioner);
    Set<Long> expectedAckedEntries1 = ImmutableSet.of(1L, 2L, 3L, 4L, 5L, 7L, 8L);

    // Partition 2
    // Entries        :1  2  3  4  5  6  7  8  9  10  11  12  13  14  15  16  17  18
    // Consumers Ack  :            1     3        2       0
    // Partition      :1  2  3  0  1  2  3  0  1  2   3   0   1   2   3   0   1   2

    groupSize = 4;
    int lastEntry = 18;

    zeroAck = 12L;
    oneAck = 5L;
    twoAck = 10L;
    long threeAck = 7L;

    partitioner =
      new TTQueueNewOnVCTable.ReconfigPartitioner(groupSize, PartitionerType.ROUND_ROBIN);
    partitioner.add(0, zeroAck);
    partitioner.add(1, oneAck);
    partitioner.add(2, twoAck);
    partitioner.add(3, threeAck);

    partitionerList.add(partitioner);
    Set<Long> expectedAckedEntries2 = ImmutableSet.of(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 10L, 12L);

    verifyTestReconfigPartitionerEmit(lastEntry, Sets.union(expectedAckedEntries1, expectedAckedEntries2),
                                      new TTQueueNewOnVCTable.ReconfigPartitionersList(partitionerList));
  }

  private void verifyTestReconfigPartitionerEmit(long queueSize, Set<Long> ackedEntries,
                                                 QueuePartitioner partitioner) {
    int groupSize = -1; // will be ignored
    int instanceId = -2; // will be ignored
    for(long entryId = 1; entryId <= queueSize; ++entryId) {
      if(ackedEntries.contains(entryId)) {
//        System.out.println("Not Emit:" + entryId);
        assertFalse("Not Emit:" + entryId, partitioner.shouldEmit(groupSize, instanceId, entryId, null));
      } else {
//        System.out.println("Emit:" + entryId);
        assertTrue("Emit:" + entryId, partitioner.shouldEmit(groupSize, instanceId, entryId, null));
      }
    }
  }

  @Test
  public void testQueueStateImplEncode() throws Exception {
    TTQueueNewOnVCTable.QueueStateImpl queueState = new TTQueueNewOnVCTable.QueueStateImpl();
    queueState.setPartitioner(FIFO);
    List<DequeueEntry> dequeueEntryList = Lists.newArrayList(new DequeueEntry(2, 1), new DequeueEntry(3, 0));
    Map<Long, byte[]> cachedEntries = ImmutableMap.of(2L, new byte[]{1, 2}, 3L, new byte[]{4, 5});

    TransientWorkingSet transientWorkingSet = new TransientWorkingSet(dequeueEntryList, 2, cachedEntries);
    queueState.setTransientWorkingSet(transientWorkingSet);

    DequeuedEntrySet dequeuedEntrySet = new DequeuedEntrySet(Sets.newTreeSet(dequeueEntryList));
    queueState.setDequeueEntrySet(dequeuedEntrySet);

    long consumerReadPointer = 3L;
    queueState.setConsumerReadPointer(consumerReadPointer);

    long queueWritePointer = 6L;
    queueState.setQueueWritePointer(queueWritePointer);

    ClaimedEntryList claimedEntryList = new ClaimedEntryList();
    claimedEntryList.add(new ClaimedEntryRange(4L, 6L));
    claimedEntryList.add(new ClaimedEntryRange(7L, 8L));
    claimedEntryList.add(new ClaimedEntryRange(10L, 20L));
    queueState.setClaimedEntryList(claimedEntryList);

    long lastEvictTimeInSecs = 124325342L;
    queueState.setLastEvictTimeInSecs(lastEvictTimeInSecs);

    ByteArrayOutputStream bos = new ByteArrayOutputStream();
    queueState.encodeTransient(new BinaryEncoder(bos));
    byte[] bytes = bos.toByteArray();

    TTQueueNewOnVCTable.QueueStateImpl actual =
      TTQueueNewOnVCTable.QueueStateImpl.decodeTransient(new BinaryDecoder(new ByteArrayInputStream(bytes)));

    // QueueStateImpl does not override equals and hashcode methods
    assertEquals(FIFO, actual.getPartitioner());
    assertEquals(transientWorkingSet, actual.getTransientWorkingSet());
    assertEquals(dequeuedEntrySet, actual.getDequeueEntrySet());
    assertEquals(consumerReadPointer, actual.getConsumerReadPointer());
    assertEquals(queueWritePointer, actual.getQueueWritePointer());
    assertEquals(claimedEntryList, actual.getClaimedEntryList());
    assertEquals(0L, actual.getLastEvictTimeInSecs()); // last evict is not encoded
  }

/*  @Override
  @Test
  public void testEvictOnAck_ThreeGroups() throws Exception {
    // Note: for now only consumer with consumerId 0 and groupId 0 can run the evict.
    TTQueue queue = createQueue();
    final boolean singleEntry = true;
    long dirtyVersion = getDirtyWriteVersion();
    ReadPointer dirtyReadPointer = getDirtyPointer();

    QueueConfig config = new QueueConfig(QueuePartitioner.PartitionerType.FIFO, singleEntry);
    QueueConsumer consumer1 = new QueueConsumer(0, 0, 1, config);
    QueueConsumer consumer2 = new QueueConsumer(0, 1, 1, config);
    QueueConsumer consumer3 = new QueueConsumer(0, 2, 1, config);

    // enable evict-on-ack for 3 groups
    int numGroups = 3;

    // enqueue 10 things
    for (int i=0; i<10; i++) {
      queue.enqueue(new QueueEntry(Bytes.toBytes(i)), dirtyVersion);
    }

    // Create consumers for checking if eviction happened
    QueueConsumer consumerCheck9thPos = new QueueConsumer(0, 3, 1, config);
    QueueConsumer consumerCheck10thPos = new QueueConsumer(0, 4, 1, config);
    // Move the consumers to 9th and 10 pos
    for(int i = 0; i < 8; i++) {
      DequeueResult result = queue.dequeue(consumerCheck9thPos, dirtyReadPointer);
      assertEquals(i, Bytes.toInt(result.getEntry().getData()));
      queue.ack(result.getEntryPointer(), consumerCheck9thPos, dirtyReadPointer);
      queue.finalize(result.getEntryPointer(), consumerCheck9thPos, -1, dirtyReadPointer.getMaximum()); // No evict
    }
    for(int i = 0; i < 9; i++) {
      DequeueResult result = queue.dequeue(consumerCheck10thPos, dirtyReadPointer);
      assertEquals(i, Bytes.toInt(result.getEntry().getData()));
      queue.ack(result.getEntryPointer(), consumerCheck10thPos, dirtyReadPointer);
      queue.finalize(result.getEntryPointer(), consumerCheck10thPos, -1, dirtyReadPointer.getMaximum()); // No evict
    }


    // dequeue/ack/finalize 8 things w/ group1 and numGroups=3
    for (int i=0; i<8; i++) {
      DequeueResult result =
        queue.dequeue(consumer1, dirtyReadPointer);
      assertEquals(i, Bytes.toInt(result.getEntry().getData()));
      queue.ack(result.getEntryPointer(), consumer1, dirtyReadPointer);
      queue.finalize(result.getEntryPointer(), consumer1, numGroups, dirtyReadPointer.getMaximum());
    }

    // dequeue is not empty, as 9th and 10th entries is still available
    assertFalse(
      queue.dequeue(consumer1, dirtyReadPointer).isEmpty());

    // dequeue with consumer2 still has entries (expected)
    assertFalse(
      queue.dequeue(consumer2, dirtyReadPointer).isEmpty());

    // dequeue everything with consumer2
    for (int i=0; i<10; i++) {
      DequeueResult result =
        queue.dequeue(consumer2, dirtyReadPointer);
      assertEquals(i, Bytes.toInt(result.getEntry().getData()));
      queue.ack(result.getEntryPointer(), consumer2, dirtyReadPointer);
      queue.finalize(result.getEntryPointer(), consumer2, numGroups, dirtyReadPointer.getMaximum());
    }

    // dequeue is empty
    assertTrue(
      queue.dequeue(consumer2, dirtyReadPointer).isEmpty());

    // dequeue with consumer3 still has entries (expected)
    assertFalse(
      queue.dequeue(consumer3, dirtyReadPointer).isEmpty());

    // dequeue everything consumer3
    for (int i=0; i<10; i++) {
      DequeueResult result =
        queue.dequeue(consumer3, dirtyReadPointer);
      assertEquals(i, Bytes.toInt(result.getEntry().getData()));
      queue.ack(result.getEntryPointer(), consumer3, dirtyReadPointer);
      queue.finalize(result.getEntryPointer(), consumer3, numGroups, dirtyReadPointer.getMaximum());
    }

    // dequeue with consumer3 is empty
    assertTrue(
      queue.dequeue(consumer3, dirtyReadPointer).isEmpty());

    // Verify 9th and 10th entries are still present
    DequeueResult result = queue.dequeue(consumerCheck9thPos, dirtyReadPointer);
    assertEquals(8, Bytes.toInt(result.getEntry().getData()));
    result = queue.dequeue(consumerCheck10thPos, dirtyReadPointer);
    assertEquals(9, Bytes.toInt(result.getEntry().getData()));

    // dequeue with consumer 1, should get 8 (9th entry)
    result = queue.dequeue(consumer1, dirtyReadPointer);
    assertEquals(8, Bytes.toInt(result.getEntry().getData()));
    queue.ack(result.getEntryPointer(), consumer1, dirtyReadPointer);
    queue.finalize(result.getEntryPointer(), consumer1, numGroups, dirtyReadPointer.getMaximum());

    // now the first 9 entries should have been physically evicted!
    // since the 9th entry does not exist anymore, exception will be thrown
    try {
    result = queue.dequeue(consumerCheck9thPos, dirtyReadPointer);
    fail("Dequeue should fail");
    } catch (OperationException e) {
      assertEquals(StatusCode.INTERNAL_ERROR, e.getStatus());
      result = null;
    }
    assertNull(result);
    result = queue.dequeue(consumerCheck10thPos, dirtyReadPointer);
    assertEquals(9, Bytes.toInt(result.getEntry().getData()));

    // dequeue with consumer 1, should get 9 (10th entry)
    result = queue.dequeue(consumer1, dirtyReadPointer);
    assertEquals(9, Bytes.toInt(result.getEntry().getData()));
    queue.ack(result.getEntryPointer(), consumer1, dirtyReadPointer);
    queue.finalize(result.getEntryPointer(), consumer1, numGroups, dirtyReadPointer.getMaximum());

    // Consumer 1 should be empty now
    assertTrue(queue.dequeue(consumer1, dirtyReadPointer).isEmpty());

    // Now 10th entry should be evicted too!
    try {
    result = queue.dequeue(consumerCheck10thPos, dirtyReadPointer);
    fail("Dequeue should fail");
    } catch (OperationException e) {
      assertEquals(StatusCode.INTERNAL_ERROR, e.getStatus());
      result = null;
    }
    assertNull(result);
  } */

  @Test
  public void testSingleConsumerWithHashPartitioning() throws Exception {
    final String HASH_KEY = "hashKey";
    final boolean singleEntry = true;
    final int numQueueEntries = 88;
    final int numConsumers = 4;
    final int consumerGroupId = 0;
    TTQueue queue = createQueue();
    long dirtyVersion = getDirtyWriteVersion();

    // enqueue some entries
    for (int i = 0; i < numQueueEntries; i++) {
      QueueEntry queueEntry = new QueueEntry(Bytes.toBytes("value" + i % numConsumers));
      queueEntry.addHashKey(HASH_KEY, i);
      assertTrue(queue.enqueue(queueEntry, dirtyVersion).isSuccess());
    }
    // dequeue it with HASH partitioner
    QueueConfig config = new QueueConfig(PartitionerType.HASH, singleEntry);

    QueueConsumer[] consumers = new QueueConsumer[numConsumers];
    for (int i = 0; i < numConsumers; i++) {
      consumers[i] = new QueueConsumer(i, consumerGroupId, numConsumers, "group1", HASH_KEY, config);
      queue.configure(consumers[i]);
    }

    // dequeue and verify
    dequeuePartitionedEntries(queue, consumers, numConsumers, numQueueEntries);

    // enqueue some more entries
    for (int i = numQueueEntries; i < numQueueEntries * 2; i++) {
      QueueEntry queueEntry = new QueueEntry(Bytes.toBytes("value" + i % numConsumers));
      queueEntry.addHashKey(HASH_KEY, i);
      assertTrue(queue.enqueue(queueEntry, dirtyVersion).isSuccess());
    }
    // dequeue and verify
    dequeuePartitionedEntries(queue, consumers, numConsumers, numQueueEntries);
  }

  @Test
  public void testSingleConsumerWithRoundRobinPartitioning() throws Exception {
    final boolean singleEntry = true;
    final int numQueueEntries = 88;
    final int numConsumers = 4;
    final int consumerGroupId = 0;
    TTQueue queue = createQueue();
    long dirtyVersion = getDirtyWriteVersion();

    // enqueue some entries
    for (int i = 0; i < numQueueEntries; i++) {
      QueueEntry queueEntry = new QueueEntry(Bytes.toBytes("value" + (i + 1) % numConsumers));
      assertTrue(queue.enqueue(queueEntry, dirtyVersion).isSuccess());
    }

    // dequeue it with ROUND_ROBIN partitioner
    QueueConfig config = new QueueConfig(PartitionerType.ROUND_ROBIN, singleEntry);

    QueueConsumer[] consumers = new QueueConsumer[numConsumers];
    for (int i = 0; i < numConsumers; i++) {
      consumers[i] = new QueueConsumer(i, consumerGroupId, numConsumers, "group1", config);
      queue.configure(consumers[i]);
    }

    // dequeue and verify
    dequeuePartitionedEntries(queue, consumers, numConsumers, numQueueEntries);

    // enqueue some more entries
    for (int i = numQueueEntries; i < numQueueEntries * 2; i++) {
      QueueEntry queueEntry = new QueueEntry(Bytes.toBytes("value" + (i + 1) % numConsumers));
      assertTrue(queue.enqueue(queueEntry, dirtyVersion).isSuccess());
    }

    // dequeue and verify
    dequeuePartitionedEntries(queue, consumers, numConsumers, numQueueEntries);
  }

  private void dequeuePartitionedEntries(TTQueue queue, QueueConsumer[] consumers, int numConsumers, int totalEnqueues) throws Exception {
    ReadPointer dirtyReadPointer = getDirtyPointer();
    for (int i = 0; i < numConsumers; i++) {
      for (int j = 0; j < totalEnqueues / (2 * numConsumers); j++) {
        DequeueResult result = queue.dequeue(consumers[i], dirtyReadPointer);
        // verify we got something and it's the first value
        assertTrue(result.toString(), result.isSuccess());
        assertEquals("value" + i, Bytes.toString(result.getEntry().getData()));
        // dequeue again without acking, should still get first value
        result = queue.dequeue(consumers[i], dirtyReadPointer);
        assertTrue(result.isSuccess());
        assertEquals("value" + i, Bytes.toString(result.getEntry().getData()));

        // ack
        queue.ack(result.getEntryPointer(), consumers[i], dirtyReadPointer);
        queue.finalize(result.getEntryPointer(), consumers[i], -1, dirtyReadPointer.getMaximum());

        // dequeue, should get second value
        result = queue.dequeue(consumers[i], dirtyReadPointer);
        assertTrue("Consumer:" + i + " Iteration:" + j, result.isSuccess());
        assertEquals("value" + i, Bytes.toString(result.getEntry().getData()));

        // ack
        queue.ack(result.getEntryPointer(), consumers[i], dirtyReadPointer);
        queue.finalize(result.getEntryPointer(), consumers[i], -1, dirtyReadPointer.getMaximum());
      }

      // verify queue is empty
      DequeueResult result = queue.dequeue(consumers[i], dirtyReadPointer);
      assertTrue(result.isEmpty());
    }
  }

  @Test
  public void testSingleStatefulConsumerWithHashPartitioning() throws Exception {
    final String HASH_KEY = "hashKey";
    final boolean singleEntry = true;
    final int numQueueEntries = 264; // Choose a number that leaves a reminder when divided by batchSize, and be big enough so that it forms a few batches
    final int numConsumers = 4;
    final int consumerGroupId = 0;
    TTQueue queue = createQueue();
    long dirtyVersion = getDirtyWriteVersion();

    // enqueue some entries
    for (int i = 0; i < numQueueEntries; i++) {
      QueueEntry queueEntry = new QueueEntry(Bytes.toBytes(i));
      queueEntry.addHashKey(HASH_KEY, i);
      assertTrue(queue.enqueue(queueEntry, dirtyVersion).isSuccess());
    }
    // dequeue it with HASH partitioner
    // TODO: test with more batch sizes
    QueueConfig config = new QueueConfig(PartitionerType.HASH, singleEntry, 29);

    StatefulQueueConsumer[] consumers = new StatefulQueueConsumer[numConsumers];
    for (int i = 0; i < numConsumers; i++) {
      consumers[i] = new StatefulQueueConsumer(i, consumerGroupId, numConsumers, "group1", HASH_KEY, config);
      queue.configure(consumers[i]);
    }

    // dequeue and verify
    dequeuePartitionedEntries(queue, consumers, numConsumers, numQueueEntries, 0, PartitionerType.HASH);
    System.out.println("Round 1 dequeue done");

    // enqueue some more entries
    for (int i = numQueueEntries; i < numQueueEntries * 2; i++) {
      QueueEntry queueEntry = new QueueEntry(Bytes.toBytes(i));
      queueEntry.addHashKey(HASH_KEY, i);
      assertTrue(queue.enqueue(queueEntry, dirtyVersion).isSuccess());
    }
    // dequeue and verify
    dequeuePartitionedEntries(queue, consumers, numConsumers, numQueueEntries, numQueueEntries, PartitionerType.HASH);
    System.out.println("Round 2 dequeue done");
  }

  @Test
  public void testSingleStatefulConsumerWithRoundRobinPartitioning() throws Exception {
    final boolean singleEntry = true;
    final int numQueueEntries = 264; // Choose a number that doesn't leave a reminder when divided by batchSize, and be big enough so that it forms a few batches
    final int numConsumers = 4;
    final int consumerGroupId = 0;
    TTQueue queue = createQueue();
    long dirtyVersion = getDirtyWriteVersion();

    // enqueue some entries
    for (int i = 0; i < numQueueEntries; i++) {
      QueueEntry queueEntry = new QueueEntry(Bytes.toBytes(i + 1));
      assertTrue(queue.enqueue(queueEntry, dirtyVersion).isSuccess());
    }

    // dequeue it with ROUND_ROBIN partitioner
    // TODO: test with more batch sizes
    QueueConfig config = new QueueConfig(PartitionerType.ROUND_ROBIN, singleEntry, 11);

    StatefulQueueConsumer[] consumers = new StatefulQueueConsumer[numConsumers];
    for (int i = 0; i < numConsumers; i++) {
      consumers[i] = new StatefulQueueConsumer(i, consumerGroupId, numConsumers, "group1", config);
      queue.configure(consumers[i]);
    }

    // dequeue and verify
    dequeuePartitionedEntries(queue, consumers, numConsumers, numQueueEntries, 0, PartitionerType
      .ROUND_ROBIN);

    // enqueue some more entries
    for (int i = numQueueEntries; i < numQueueEntries * 2; i++) {
      QueueEntry queueEntry = new QueueEntry(Bytes.toBytes(i + 1));
      assertTrue(queue.enqueue(queueEntry, dirtyVersion).isSuccess());
    }

    // dequeue and verify
    dequeuePartitionedEntries(queue, consumers, numConsumers, numQueueEntries, numQueueEntries, PartitionerType.ROUND_ROBIN);
  }

  private void dequeuePartitionedEntries(TTQueue queue, StatefulQueueConsumer[] consumers, int numConsumers,
                                         int numQueueEntries, int startQueueEntry, PartitionerType partitionerType) throws Exception {
    ReadPointer dirtyReadPointer = getDirtyPointer();
    for (int consumer = 0; consumer < numConsumers; consumer++) {
      for (int entry = 0; entry < numQueueEntries / (2 * numConsumers); entry++) {
        DequeueResult result = queue.dequeue(consumers[consumer], dirtyReadPointer);
        // verify we got something and it's the first value
        assertTrue(result.toString(), result.isSuccess());
        int expectedValue = startQueueEntry + consumer + (2 * entry * numConsumers);
        if(partitionerType == PartitionerType.ROUND_ROBIN) {
          if(consumer == 0) {
            // Adjust the expected value for consumer 0
            expectedValue += numConsumers;
          }
        }
//        System.out.println(String.format("Consumer-%d entryid=%d value=%s expectedValue=%s",
//                  consumer, result.getEntryPointer().getEntryId(), Bytes.toInt(result.getEntry().getData()), expectedValue));
        assertEquals(expectedValue, Bytes.toInt(result.getEntry().getData()));
        // dequeue again without acking, should still get first value
        result = queue.dequeue(consumers[consumer], dirtyReadPointer);
        assertTrue(result.isSuccess());
        assertEquals(expectedValue, Bytes.toInt(result.getEntry().getData()));

        // ack
        queue.ack(result.getEntryPointer(), consumers[consumer], dirtyReadPointer);
        queue.finalize(result.getEntryPointer(), consumers[consumer], -1, dirtyReadPointer.getMaximum());

        // dequeue, should get second value
        result = queue.dequeue(consumers[consumer], dirtyReadPointer);
        assertTrue("Consumer:" + consumer + " Entry:" + entry, result.isSuccess());
        expectedValue += numConsumers;
//        System.out.println(String.format("Consumer-%d entryid=%d value=%s expectedValue=%s",
//                  consumer, result.getEntryPointer().getEntryId(), Bytes.toInt(result.getEntry().getData()), expectedValue));
        assertEquals(expectedValue, Bytes.toInt(result.getEntry().getData()));

        // ack
        queue.ack(result.getEntryPointer(), consumers[consumer], dirtyReadPointer);
        queue.finalize(result.getEntryPointer(), consumers[consumer], -1, dirtyReadPointer.getMaximum());
      }

      // verify queue is empty
      DequeueResult result = queue.dequeue(consumers[consumer], dirtyReadPointer);
      assertTrue(result.isEmpty());
    }
  }

  @Test
  public void testSingleStatefulConsumerWithFifo() throws Exception {
    final boolean singleEntry = true;
    final int numQueueEntries = 200;  // Make sure numQueueEntries % batchSize == 0 && numQueueEntries % numConsumers == 0
    final int numConsumers = 4;
    final int consumerGroupId = 0;
    TTQueue queue = createQueue();
    long dirtyVersion = getDirtyWriteVersion();

    // enqueue some entries
    for (int i = 0; i < numQueueEntries; i++) {
      QueueEntry queueEntry = new QueueEntry(Bytes.toBytes(i));
      assertTrue(queue.enqueue(queueEntry, dirtyVersion).isSuccess());
    }

    // dequeue it with ROUND_ROBIN partitioner
    // TODO: test with more batch sizes
    QueueConfig config = new QueueConfig(FIFO, singleEntry, 10);

    StatefulQueueConsumer[] statefulQueueConsumers = new StatefulQueueConsumer[numConsumers];
    QueueConsumer[] queueConsumers = new QueueConsumer[numConsumers];
    for (int i = 0; i < numConsumers; i++) {
      statefulQueueConsumers[i] = new StatefulQueueConsumer(i, consumerGroupId, numConsumers, "group1", config);
      queueConsumers[i] = new QueueConsumer(i, consumerGroupId, numConsumers, "group1", config);
      queue.configure(queueConsumers[i]);
    }

    // dequeue and verify
    dequeueFifoEntries(queue, statefulQueueConsumers, numConsumers, numQueueEntries, 0);

    // enqueue some more entries
    for (int i = numQueueEntries; i < 2 * numQueueEntries; i++) {
      QueueEntry queueEntry = new QueueEntry(Bytes.toBytes(i));
      assertTrue(queue.enqueue(queueEntry, dirtyVersion).isSuccess());
    }

    // dequeue and verify
    dequeueFifoEntries(queue, statefulQueueConsumers, numConsumers, numQueueEntries, numQueueEntries);

    // Run with stateless QueueConsumer
    for (int i = 2 * numQueueEntries; i < 3 * numQueueEntries; i++) {
      QueueEntry queueEntry = new QueueEntry(Bytes.toBytes(i));
      assertTrue(queue.enqueue(queueEntry, dirtyVersion).isSuccess());
    }

    // dequeue and verify
    dequeueFifoEntries(queue, queueConsumers, numConsumers, numQueueEntries, 2 * numQueueEntries);

  }

  private void dequeueFifoEntries(TTQueue queue, QueueConsumer[] consumers, int numConsumers,
                                  int numQueueEntries, int startQueueEntry) throws Exception {
    ReadPointer dirtyReadPointer = getDirtyPointer();
    int expectedValue = startQueueEntry;
    for (int consumer = 0; consumer < numConsumers; consumer++) {
      for (int entry = 0; entry < numQueueEntries / (2 * numConsumers); entry++, ++expectedValue) {
        DequeueResult result = queue.dequeue(consumers[consumer], dirtyReadPointer);
        // verify we got something and it's the first value
        assertTrue(String.format("Consumer=%d, entry=%d, %s", consumer, entry, result.toString()), result.isSuccess());
//        System.out.println(String.format("Consumer-%d entryid=%d value=%s expectedValue=%s",
//                  consumer, result.getEntryPointer().getEntryId(), Bytes.toInt(result.getEntry().getData()), expectedValue));
        assertEquals(expectedValue, Bytes.toInt(result.getEntry().getData()));
        // dequeue again without acking, should still get first value
        result = queue.dequeue(consumers[consumer], dirtyReadPointer);
        assertTrue(result.isSuccess());
        assertEquals(expectedValue, Bytes.toInt(result.getEntry().getData()));

        // ack
        queue.ack(result.getEntryPointer(), consumers[consumer], dirtyReadPointer);
        queue.finalize(result.getEntryPointer(), consumers[consumer], -1, dirtyReadPointer.getMaximum());

        // dequeue, should get second value
        result = queue.dequeue(consumers[consumer], dirtyReadPointer);
        assertTrue(result.isSuccess());
        ++expectedValue;
//        System.out.println(String.format("Consumer-%d entryid=%d value=%s expectedValue=%s",
//                  consumer, result.getEntryPointer().getEntryId(), Bytes.toInt(result.getEntry().getData()), expectedValue));
        assertEquals(expectedValue, Bytes.toInt(result.getEntry().getData()));

        // ack
        queue.ack(result.getEntryPointer(), consumers[consumer], dirtyReadPointer);
        queue.finalize(result.getEntryPointer(), consumers[consumer], -1, dirtyReadPointer.getMaximum());
      }
    }

    // verify queue is empty for all consumers
    for(int consumer = 0; consumer < numConsumers; ++consumer) {
      DequeueResult result = queue.dequeue(consumers[consumer], dirtyReadPointer);
      assertTrue(result.isEmpty());
    }
  }

  @Test
  public void testMaxCrashDequeueTries() throws Exception {
    TTQueue queue = createQueue();
    final long groupId = 0;
    final int instanceId = 0;
    final int groupSize = 1;

    assertTrue(queue.enqueue(new QueueEntry(Bytes.toBytes(1)), getDirtyWriteVersion()).isSuccess());
    assertTrue(queue.enqueue(new QueueEntry(Bytes.toBytes(2)), getDirtyWriteVersion()).isSuccess());

    // dequeue it with FIFO partitioner, single entry mode
    QueueConfig config = new QueueConfig(FIFO, true);

    queue.configure(new StatefulQueueConsumer(instanceId, groupId, groupSize, "", config));

    for(int tries = 0; tries <= MAX_CRASH_DEQUEUE_TRIES; ++tries) {
      // Simulate consumer crashing by sending in empty state every time and not acking the entry
      DequeueResult result = queue.dequeue(new StatefulQueueConsumer(instanceId, groupId, groupSize, "", config),
                                           getDirtyPointer());
      assertTrue(result.isSuccess());
      assertEquals(1, Bytes.toInt(result.getEntry().getData()));
    }

    // After max tries, the entry will be ignored
    StatefulQueueConsumer statefulQueueConsumer =
      new StatefulQueueConsumer(instanceId, groupId, groupSize, "", config);
    for(int tries = 0; tries <= MAX_CRASH_DEQUEUE_TRIES + 10; ++tries) {
      // No matter how many times a dequeue is repeated with state, the same entry needs to be returned
      DequeueResult result = queue.dequeue(statefulQueueConsumer, getDirtyPointer());
      assertTrue(result.isSuccess());
      assertEquals("Tries=" + tries, 2, Bytes.toInt(result.getEntry().getData()));
    }
    DequeueResult result = queue.dequeue(statefulQueueConsumer, getDirtyPointer());
    assertTrue(result.isSuccess());
    assertEquals(2, Bytes.toInt(result.getEntry().getData()));
    queue.ack(result.getEntryPointer(), statefulQueueConsumer, getDirtyPointer());

    result = queue.dequeue(statefulQueueConsumer, getDirtyPointer());
    assertTrue(result.isEmpty());
  }

  @Test
  public void testFifoReconfig() throws Exception {
    Condition condition = new Condition() {
      @Override
      public boolean check(long entryId, int groupSize, long instanceId, int hash) {
        return true;
      }
    };

    PartitionerType partitionerType = FIFO;

    runConfigTest(partitionerType, condition);
  }

  @Test
  public void testRoundRobinReconfig() throws Exception {
    Condition condition = new Condition() {
      @Override
      public boolean check(long entryId, int groupSize, long instanceId, int hash) {
        return entryId % groupSize == instanceId;
      }
    };

    PartitionerType partitionerType = PartitionerType.ROUND_ROBIN;

    runConfigTest(partitionerType, condition);
  }

  @Test
  public void testHashReconfig() throws Exception {
    Condition condition = new Condition() {
      @Override
      public boolean check(long entryId, int groupSize, long instanceId, int hash) {
        return hash % groupSize == instanceId;
      }
    };

    PartitionerType partitionerType = PartitionerType.HASH;

    runConfigTest(partitionerType, condition);
  }

  public void runConfigTest(PartitionerType partitionerType, Condition condition) throws Exception {
    testReconfig(Lists.newArrayList(3, 2), 54, 5, 6, partitionerType, condition);
    testReconfig(Lists.newArrayList(3, 3, 4, 2), 144, 5, 9, partitionerType, condition);
    testReconfig(Lists.newArrayList(3, 5, 2, 1, 6, 2), 200, 5, 9, partitionerType, condition);
    testReconfig(Lists.newArrayList(3, 5, 2, 1, 6, 2), 200, 9, 9, partitionerType, condition);
    testReconfig(Lists.newArrayList(3, 5, 2, 1, 6, 2), 200, 9, 5, partitionerType, condition);
    testReconfig(Lists.newArrayList(1, 2, 3, 4, 5, 4, 3, 2, 1), 300, 9, 5, partitionerType, condition);
    // this failed before claimed entry lists were sorted
    testReconfig(Lists.newArrayList(3, 5, 2, 1, 6, 2), 50, 5, 3, partitionerType, condition);
  }

  private static final String HASH_KEY = "HashKey";
  interface Condition {
    boolean check(long entryId, int groupSize, long instanceId, int hash);
  }

  // TODO: test with stateful and non-state consumer
  private void testReconfig(List<Integer> consumerCounts, final int numEntries, final int queueBatchSize,
                            final int perConsumerDequeueBatchSize, PartitionerType partitionerType,
                            Condition condition) throws Exception {
    Random random = new Random(System.currentTimeMillis());
    StringWriter debugCollector = new StringWriter();
    TTQueue queue = createQueue();

    List<Integer> expectedEntries = Lists.newArrayList();
    // Enqueue numEntries
    for(int i = 0; i < numEntries; ++i) {
      expectedEntries.add(i + 1);
      QueueEntry queueEntry = new QueueEntry(Bytes.toBytes(i + 1));
      queueEntry.addHashKey(HASH_KEY, i + 1);
      assertTrue(debugCollector.toString(), queue.enqueue(queueEntry, getDirtyWriteVersion()).isSuccess());
    }

    expectedEntries = ImmutableList.copyOf(expectedEntries);
    assertEquals(debugCollector.toString(), numEntries, expectedEntries.size());

    List<Integer> actualEntries = Lists.newArrayList();
    List<String> actualPrintEntries = Lists.newArrayList();
    List<Integer> sortedActualEntries;
    List<StatefulQueueConsumer> consumers;
    // dequeue it with FIFO partitioner, single entry mode
    QueueConfig config = new QueueConfig(partitionerType, true, queueBatchSize);
    long groupId = queue.getGroupID();
    int expectedOldConsumerCount = 0;

    loop:
    while(true) {
      for(Integer newConsumerCount : consumerCounts) {
        // Create new consumers
        consumers = Lists.newArrayListWithCapacity(newConsumerCount);
        int actualOldConsumerCount = -1;
        for(int i = 0; i < newConsumerCount; ++i) {
          StatefulQueueConsumer consumer;
          if(partitionerType != PartitionerType.HASH) {
            consumer = new StatefulQueueConsumer(i, groupId, newConsumerCount, config);
          } else {
            consumer = new StatefulQueueConsumer(i, groupId, newConsumerCount, "", HASH_KEY, config);
          }
          consumers.add(consumer);
          debugCollector.write(String.format("Running configure...%n"));
          int oldConsumerCount = queue.configure(consumer);
          if(oldConsumerCount >= 0) {
            actualOldConsumerCount = oldConsumerCount;
          }
        }
        debugCollector.write(String.format("Old consumer count = %d, new consumer count = %s%n", actualOldConsumerCount, newConsumerCount));
        assertEquals(debugCollector.toString(), expectedOldConsumerCount, actualOldConsumerCount);

      // Dequeue entries with random batch size and random consumers each time
        int numTriesThisRun = 0;
        int numDequeuesThisRun = 0;
        List<StatefulQueueConsumer> workingConsumerList = Lists.newLinkedList(consumers);
        while(!workingConsumerList.isEmpty()) {
          QueueConsumer consumer = workingConsumerList.remove(random.nextInt(workingConsumerList.size()));
          //QueueConsumer consumer = workingConsumerList.remove(0);
          int curBatchSize = random.nextInt(perConsumerDequeueBatchSize + 1);
          //int curBatchSize = perConsumerDequeueBatchSize;
          debugCollector.write(String.format("Current batch size = %d%n", curBatchSize));

          for(int i = 0; i < curBatchSize; ++i) {
            ++numTriesThisRun;
            DequeueResult result = queue.dequeue(consumer, getDirtyPointer());
            debugCollector.write(consumer.getInstanceId() + " dequeued " +
                                   (result.isEmpty() ? "<empty>" : "" + result.getEntryPointer().getEntryId()) +
                                   ", state: " + consumer.getQueueState() + "\n");
            if(result.isEmpty()) {
              break;
            }
            ++numDequeuesThisRun;
            actualEntries.add(Bytes.toInt(result.getEntry().getData()));
            actualPrintEntries.add(consumer.getInstanceId() + ":" + Bytes.toInt(result.getEntry().getData()));
            queue.ack(result.getEntryPointer(), consumer, getDirtyPointer());
            queue.finalize(result.getEntryPointer(), consumer, 1, getDirtyWriteVersion());
            assertTrue(debugCollector.toString(),
                       condition.check(
                         result.getEntryPointer().getEntryId(),
                         newConsumerCount,
                         consumer.getInstanceId(),
                         (int) result.getEntryPointer().getEntryId()
                       ));
          }
          actualEntries.add(-1);
        }
        debugCollector.write(String.format("%s%n", actualPrintEntries));
        debugCollector.write(String.format("%s%n", actualEntries));
        sortedActualEntries = Lists.newArrayList(actualEntries);
        Collections.sort(sortedActualEntries);
        debugCollector.write(String.format("%s%n", sortedActualEntries));

        // If all consumers report queue empty then stop
        if(numDequeuesThisRun == 0 && numTriesThisRun >= consumers.size()) {
          sortedActualEntries.removeAll(Lists.newArrayList(-1));
          debugCollector.write(String.format("Expected: %s%n", expectedEntries));
          debugCollector.write(String.format("Actual:   %s%n", sortedActualEntries));
          break loop;
        }
        expectedOldConsumerCount = newConsumerCount;
      }
    }

    // Make sure the queue is empty
    for(QueueConsumer consumer : consumers) {
      DequeueResult result = queue.dequeue(consumer, getDirtyPointer());
      assertTrue(debugCollector.toString(), result.isEmpty());
    }

    assertEquals(debugCollector.toString(), expectedEntries, sortedActualEntries);
  }

  // Tests that do not work on NewTTQueue

  /**
   * Currently not working.  Will be fixed in ENG-???.
   */
  @Override
  @Test
  @Ignore
  public void testSingleConsumerSingleEntryWithInvalid_Empty_ChangeSizeAndToMulti() {
  }

  @Override
  @Test
  @Ignore
  public void testSingleConsumerMultiEntry_Empty_ChangeToSingleConsumerSingleEntry() {
  }

  @Override
  @Test
  @Ignore
  public void testSingleConsumerSingleGroup_dynamicReconfig() {
  }

  @Override
  @Test
  @Ignore
  public void testMultiConsumerSingleGroup_dynamicReconfig() {
  }

  @Override
  @Test
  @Ignore
  public void testMultipleConsumerMultiTimeouts() {
  }

  @Override @Test @Ignore
  public void testMultiConsumerMultiGroup() {}

  @Override
  @Test
  @Ignore
  public void testSingleConsumerAckSemantics() {
  }

  @Override
  @Test @Ignore
  public void testSingleConsumerWithHashValuePartitioning() throws Exception {
  }

  @Test
  public void testBatchSyncDisjoint() throws OperationException {
    testBatchSyncDisjoint(PartitionerType.HASH, false);
    testBatchSyncDisjoint(PartitionerType.HASH, true);
    testBatchSyncDisjoint(PartitionerType.ROUND_ROBIN, false);
    testBatchSyncDisjoint(PartitionerType.ROUND_ROBIN, true);
  }

  public void testBatchSyncDisjoint(PartitionerType partitioner, boolean simulateCrash)
    throws OperationException {

    TTQueue queue = createQueue();

    // enqueue 100
    String hashKey = "h";
    QueueEntry[] entries = new QueueEntry[100];
    for (int i = 1; i <= entries.length; i++) {
      entries[i - 1] = new QueueEntry(Collections.singletonMap(hashKey, i), Bytes.toBytes(i));
    }
    Transaction t = oracle.startTransaction();
    queue.enqueue(entries, t.getWriteVersion());
    oracle.commitTransaction(t);

    // we will dequeue with the given partitioning, two consumers in group, hence returns every other entry
    long groupId = 42;
    String groupName = "group";
    QueueConfig config = new QueueConfig(partitioner, true, 15, true);
    QueueConsumer consumer = simulateCrash ?
      // stateless consumer requires reconstruction of state every time, like after a crash
      new QueueConsumer(0, groupId, 2, groupName, hashKey, config) :
      new StatefulQueueConsumer(0, groupId, 2, groupName, hashKey, config);
    queue.configure(consumer);

    // dequeue 15 should return entries: 2, 4, .., 30
    t = oracle.startTransaction();
    DequeueResult result = queue.dequeue(consumer, t.getReadPointer());
    oracle.commitTransaction(t);
    assertFalse(result.isEmpty());
    entries = result.getEntries();
    assertEquals(15, entries.length);
    for (int i = 0; i < 15; i++) {
      assertEquals(2 * (i + 1), Bytes.toInt(entries[i].getData()));
    }

    // dequeue 15 again, should return the same entries
    t = oracle.startTransaction();
    result = queue.dequeue(consumer, t.getReadPointer());
    oracle.commitTransaction(t);
    assertFalse(result.isEmpty());
    entries = result.getEntries();
    assertEquals(15, entries.length);
    for (int i = 0; i < 15; i++) {
      assertEquals(2 * (i + 1), Bytes.toInt(entries[i].getData()));
    }

    // ack all entries received, and then unack them
    t = oracle.startTransaction();
    QueueEntryPointer[] pointers = result.getEntryPointers();
    queue.ack(pointers, consumer, t.getReadPointer());
    queue.unack(pointers, consumer, t.getReadPointer());
    oracle.commitTransaction(t);

    // dequeue 15 again, should return the same entries
    t = oracle.startTransaction();
    result = queue.dequeue(consumer, t.getReadPointer());
    oracle.commitTransaction(t);
    assertFalse(result.isEmpty());
    entries = result.getEntries();
    assertEquals(15, entries.length);
    for (int i = 0; i < 15; i++) {
      assertEquals(2 * (i + 1), Bytes.toInt(entries[i].getData()));
    }

    // ack all entries received, and then finalize them
    t = oracle.startTransaction();
    pointers = result.getEntryPointers();
    queue.ack(pointers, consumer, t.getReadPointer());
    queue.finalize(pointers, consumer, 2, t.getWriteVersion());
    oracle.commitTransaction(t);

    // dequeue 15 again, should now return new ones 32, 34, .. 60
    t = oracle.startTransaction();
    result = queue.dequeue(consumer, t.getReadPointer());
    oracle.commitTransaction(t);
    assertFalse(result.isEmpty());
    entries = result.getEntries();
    assertEquals(15, entries.length);
    for (int i = 0; i < 15; i++) {
      assertEquals(2 * (16 + i), Bytes.toInt(entries[i].getData()));
    }

    // ack 10 + finalize
    t = oracle.startTransaction();
    pointers = Arrays.copyOf(result.getEntryPointers(), 10);
    queue.ack(pointers, consumer, t.getReadPointer());
    queue.finalize(pointers, consumer, 2, t.getWriteVersion());
    oracle.commitTransaction(t);

    // dequeue 15 again, should return the 5 previous ones again: 52, 54, ..., 60
    t = oracle.startTransaction();
    result = queue.dequeue(consumer, t.getReadPointer());
    oracle.commitTransaction(t);
    assertFalse(result.isEmpty());
    entries = result.getEntries();
    assertEquals(5, entries.length);
    for (int i = 0; i < 5; i++) {
      assertEquals(2 * (26 + i), Bytes.toInt(entries[i].getData()));
    }

    // ack the 5 + finalize
    t = oracle.startTransaction();
    pointers = result.getEntryPointers();
    queue.ack(pointers, consumer, t.getReadPointer());
    queue.finalize(pointers, consumer, 2, t.getWriteVersion());
    oracle.commitTransaction(t);

    // dequeue 15 again, should return 15 new ones: 62, ..., 90
    t = oracle.startTransaction();
    result = queue.dequeue(consumer, t.getReadPointer());
    oracle.commitTransaction(t);
    assertFalse(result.isEmpty());
    entries = result.getEntries();
    assertEquals(15, entries.length);
    for (int i = 0; i < 15; i++) {
      assertEquals(2 * (31 + i), Bytes.toInt(entries[i].getData()));
    }

    // now we change the batch size for the consumer to 12 (reconfigure)
    config = new QueueConfig(partitioner, true, 12, true);
    consumer = simulateCrash ?
      new QueueConsumer(0, groupId, 2, groupName, hashKey, config) :
      new StatefulQueueConsumer(0, groupId, 2, groupName, hashKey, config);
    queue.configure(consumer);

    // dequeue 12 with new consumer, should return the first 12 of previous 15: 62, ..., 84
    t = oracle.startTransaction();
    result = queue.dequeue(consumer, t.getReadPointer());
    oracle.commitTransaction(t);
    assertFalse(result.isEmpty());
    entries = result.getEntries();
    assertEquals(12, entries.length);
    for (int i = 0; i < 12; i++) {
      assertEquals(2 * (31 + i), Bytes.toInt(entries[i].getData()));
    }

    // ack all 12 + finalize
    t = oracle.startTransaction();
    pointers = result.getEntryPointers();
    queue.ack(pointers, consumer, t.getReadPointer());
    queue.finalize(pointers, consumer, 2, t.getWriteVersion());
    oracle.commitTransaction(t);

    // dequeue 12 again, should return the remaining 3  of previous 15: 86, 88, 90
    t = oracle.startTransaction();
    result = queue.dequeue(consumer, t.getReadPointer());
    oracle.commitTransaction(t);
    assertFalse(result.isEmpty());
    entries = result.getEntries();
    assertEquals(3, entries.length);
    for (int i = 0; i < 3; i++) {
      assertEquals(2 * (43 + i), Bytes.toInt(entries[i].getData()));
    }

    // ack all of them + finalize
    t = oracle.startTransaction();
    pointers = result.getEntryPointers();
    queue.ack(pointers, consumer, t.getReadPointer());
    queue.finalize(pointers, consumer, 2, t.getWriteVersion());
    oracle.commitTransaction(t);

    // dequeue another 12, should return only 5 remaining: 92, 94, ..., 100
    t = oracle.startTransaction();
    result = queue.dequeue(consumer, t.getReadPointer());
    oracle.commitTransaction(t);
    assertFalse(result.isEmpty());
    entries = result.getEntries();
    assertEquals(5, entries.length);
    for (int i = 0; i < 5; i++) {
      assertEquals(2 * (46 + i), Bytes.toInt(entries[i].getData()));
    }

    // ack all of them + finalize
    t = oracle.startTransaction();
    pointers = result.getEntryPointers();
    queue.ack(pointers, consumer, t.getReadPointer());
    queue.finalize(pointers, consumer, 2, t.getWriteVersion());
    oracle.commitTransaction(t);

    // dequeue should return empty
    t = oracle.startTransaction();
    result = queue.dequeue(consumer, t.getReadPointer());
    oracle.commitTransaction(t);
    assertTrue(result.isEmpty());
  }

  @Test
  public void testBatchSyncFifo() throws OperationException {
    testBatchSyncFifo(false);
    testBatchSyncFifo(true);
  }

  public void testBatchSyncFifo(boolean simulateCrash) throws OperationException {

    TTQueue queue = createQueue();

    // enqueue a batch
    QueueEntry[] entries = new QueueEntry[75];
    for (int i = 1; i <= entries.length; i++) {
      entries[i - 1] = new QueueEntry(Bytes.toBytes(i));
    }
    Transaction t = oracle.startTransaction();
    queue.enqueue(entries, t.getWriteVersion());
    oracle.commitTransaction(t);

    // we will dequeue with fifo partitioning, two consumers in group, hence returns every other entry
    long groupId = 42;
    String groupName = "group";
    QueueConfig config = new QueueConfig(FIFO, true, 15, true);
    QueueConsumer consumer1 = simulateCrash ?
      new QueueConsumer(0, groupId, 2, groupName, config) :
      new StatefulQueueConsumer(0, groupId, 2, groupName, config);
    QueueConsumer consumer2 = simulateCrash ?
      new QueueConsumer(1, groupId, 2, groupName, config) :
      new StatefulQueueConsumer(1, groupId, 2, groupName, config);
    queue.configure(consumer1);

    // dequeue 15 should return first 15 entries: 1, 2, .., 15
    t = oracle.startTransaction();
    DequeueResult result1 = queue.dequeue(consumer1, t.getReadPointer());
    oracle.commitTransaction(t);
    assertFalse(result1.isEmpty());
    entries = result1.getEntries();
    assertEquals(15, entries.length);
    for (int i = 0; i < 15; i++) {
      assertEquals(i + 1, Bytes.toInt(entries[i].getData()));
    }

    // dequeue 15 again, should return the same entries
    t = oracle.startTransaction();
    result1 = queue.dequeue(consumer1, t.getReadPointer());
    oracle.commitTransaction(t);
    assertFalse(result1.isEmpty());
    entries = result1.getEntries();
    assertEquals(15, entries.length);
    for (int i = 0; i < 15; i++) {
      assertEquals(i + 1, Bytes.toInt(entries[i].getData()));
    }

    // attempt to ack with different consumer -> fail
    t = oracle.startTransaction();
    QueueEntryPointer[] pointers = result1.getEntryPointers();
    try {
      queue.ack(pointers, consumer2, t.getReadPointer());
      fail("ack sould have failed due to wrong consumer");
    } catch (OperationException e) {
      // expect ILLEGAL_ACK
      if (e.getStatus() != StatusCode.ILLEGAL_ACK) {
        throw e;
      }
    }
    oracle.commitTransaction(t);

    // dequeue with other consumer should now return next 15 entries: 16, 17, .., 30
    t = oracle.startTransaction();
    DequeueResult result2 = queue.dequeue(consumer2, t.getReadPointer());
    oracle.commitTransaction(t);
    assertFalse(result1.isEmpty());
    entries = result2.getEntries();
    assertEquals(15, entries.length);
    for (int i = 0; i < 15; i++) {
      assertEquals(i + 16, Bytes.toInt(entries[i].getData()));
    }

    // ack all entries received, and then unack them
    t = oracle.startTransaction();
    pointers = result1.getEntryPointers();
    queue.ack(pointers, consumer1, t.getReadPointer());
    queue.unack(pointers, consumer1, t.getReadPointer());
    oracle.commitTransaction(t);

    // dequeue 15 again, should return the same entries
    t = oracle.startTransaction();
    result1 = queue.dequeue(consumer1, t.getReadPointer());
    oracle.commitTransaction(t);
    assertFalse(result1.isEmpty());
    entries = result1.getEntries();
    assertEquals(15, entries.length);
    for (int i = 0; i < 15; i++) {
      assertEquals(i + 1, Bytes.toInt(entries[i].getData()));
    }

    // ack all entries received, and then finalize them
    t = oracle.startTransaction();
    pointers = result1.getEntryPointers();
    queue.ack(pointers, consumer1, t.getReadPointer());
    queue.finalize(pointers, consumer1, 2, t.getWriteVersion());
    oracle.commitTransaction(t);

    // dequeue 15 again, should now return new ones 31, 32, .. 45
    t = oracle.startTransaction();
    result1 = queue.dequeue(consumer1, t.getReadPointer());
    oracle.commitTransaction(t);
    assertFalse(result1.isEmpty());
    entries = result1.getEntries();
    assertEquals(15, entries.length);
    for (int i = 0; i < 15; i++) {
      assertEquals(31 + i, Bytes.toInt(entries[i].getData()));
    }

    // ack 10 + finalize
    t = oracle.startTransaction();
    pointers = Arrays.copyOf(result1.getEntryPointers(), 10);
    queue.ack(pointers, consumer1, t.getReadPointer());
    queue.finalize(pointers, consumer1, 2, t.getWriteVersion());
    oracle.commitTransaction(t);

    // dequeue 15 again, should return the 5 previous ones again: 41, 42, ..., 45
    t = oracle.startTransaction();
    result1 = queue.dequeue(consumer1, t.getReadPointer());
    oracle.commitTransaction(t);
    assertFalse(result1.isEmpty());
    entries = result1.getEntries();
    assertEquals(5, entries.length);
    for (int i = 0; i < 5; i++) {
      assertEquals(41 + i, Bytes.toInt(entries[i].getData()));
    }

    // ack the 5 + finalize
    t = oracle.startTransaction();
    pointers = result1.getEntryPointers();
    queue.ack(pointers, consumer1, t.getReadPointer());
    queue.finalize(pointers, consumer1, 2, t.getWriteVersion());
    oracle.commitTransaction(t);

    // dequeue 15 again, should return 15 new ones: 46, ..., 60
    t = oracle.startTransaction();
    result1 = queue.dequeue(consumer1, t.getReadPointer());
    oracle.commitTransaction(t);
    assertFalse(result1.isEmpty());
    entries = result1.getEntries();
    assertEquals(15, entries.length);
    for (int i = 0; i < 15; i++) {
      assertEquals(46 + i, Bytes.toInt(entries[i].getData()));
    }

    // now we change the batch size for the consumer to 12 (reconfigure)
    config = new QueueConfig(FIFO, true, 12, true);
    consumer1 = simulateCrash ?
      new QueueConsumer(0, groupId, 2, groupName, config) :
      new StatefulQueueConsumer(0, groupId, 2, groupName, config);
    consumer2 = simulateCrash ?
      new QueueConsumer(1, groupId, 2, groupName, config) :
      new StatefulQueueConsumer(1, groupId, 2, groupName, config);
    queue.configure(consumer1);

    // dequeue 12 with new consumer, should return the first 12 of previous 15: 46, 47, ..., 57
    t = oracle.startTransaction();
    result1 = queue.dequeue(consumer1, t.getReadPointer());
    oracle.commitTransaction(t);
    assertFalse(result1.isEmpty());
    entries = result1.getEntries();
    assertEquals(12, entries.length);
    for (int i = 0; i < 12; i++) {
      assertEquals(46 + i, Bytes.toInt(entries[i].getData()));
    }

    // dequeue 12 with new consumer 2, should return the first 12 of previous: 16, 17, ..., 27
    t = oracle.startTransaction();
    result2 = queue.dequeue(consumer2, t.getReadPointer());
    oracle.commitTransaction(t);
    assertFalse(result2.isEmpty());
    entries = result2.getEntries();
    assertEquals(12, entries.length);
    for (int i = 0; i < 12; i++) {
      assertEquals(16 + i, Bytes.toInt(entries[i].getData()));
    }

    // ack all 12 + finalize
    t = oracle.startTransaction();
    pointers = result1.getEntryPointers();
    queue.ack(pointers, consumer1, t.getReadPointer());
    queue.finalize(pointers, consumer1, 2, t.getWriteVersion());
    pointers = result2.getEntryPointers();
    queue.ack(pointers, consumer2, t.getReadPointer());
    queue.finalize(pointers, consumer2, 2, t.getWriteVersion());
    oracle.commitTransaction(t);

    // dequeue 12 again, should return the remaining 3  of previous 15: 58, 59, 60
    t = oracle.startTransaction();
    result1 = queue.dequeue(consumer1, t.getReadPointer());
    oracle.commitTransaction(t);
    assertFalse(result1.isEmpty());
    entries = result1.getEntries();
    assertEquals(3, entries.length);
    for (int i = 0; i < 3; i++) {
      assertEquals(58 + i, Bytes.toInt(entries[i].getData()));
    }

    // dequeue 12 again with consumer 2, should return the remaining 3  of previous 15: 28, 29, 30
    t = oracle.startTransaction();
    result2 = queue.dequeue(consumer2, t.getReadPointer());
    oracle.commitTransaction(t);
    assertFalse(result2.isEmpty());
    entries = result2.getEntries();
    assertEquals(3, entries.length);
    for (int i = 0; i < 3; i++) {
      assertEquals(28 + i, Bytes.toInt(entries[i].getData()));
    }

    // ack all of them + finalize
    t = oracle.startTransaction();
    pointers = result1.getEntryPointers();
    queue.ack(pointers, consumer1, t.getReadPointer());
    queue.finalize(pointers, consumer1, 2, t.getWriteVersion());
    pointers = result2.getEntryPointers();
    queue.ack(pointers, consumer2, t.getReadPointer());
    queue.finalize(pointers, consumer2, 2, t.getWriteVersion());
    oracle.commitTransaction(t);

    // dequeue another 12, should return some (around 5) of the 15 remaining: 61, 62, ...
    t = oracle.startTransaction();
    result1 = queue.dequeue(consumer1, t.getReadPointer());
    oracle.commitTransaction(t);
    assertFalse(result1.isEmpty());
    entries = result1.getEntries();
    assertTrue(entries.length > 2 && entries.length < 10);
    for (int i = 0; i < entries.length; i++) {
      assertEquals(61 + i, Bytes.toInt(entries[i].getData()));
    }
  }

  @Test
  public void testBatchAsyncDisjoint() throws OperationException {
    testBatchAsyncDisjoint(PartitionerType.HASH);
    testBatchAsyncDisjoint(PartitionerType.ROUND_ROBIN);
  }

  public void testBatchAsyncDisjoint(PartitionerType partitioner) throws OperationException {

    TTQueue queue = createQueue();

    // enqueue 100
    String hashKey = "h";
    QueueEntry[] entries = new QueueEntry[100];
    for (int i = 1; i <= entries.length; i++) {
      entries[i - 1] = new QueueEntry(Collections.singletonMap(hashKey, i), Bytes.toBytes(i));
    }
    Transaction t = oracle.startTransaction();
    queue.enqueue(entries, t.getWriteVersion());
    oracle.commitTransaction(t);

    // we will dequeue with the given partitioning, two consumers in group, hence returns every other entry
    long groupId = 42;
    String groupName = "group";
    QueueConfig config = new QueueConfig(partitioner, false, 15, true);
    QueueConsumer consumer0 = new StatefulQueueConsumer(0, groupId, 2, groupName, hashKey, config);
    QueueConsumer consumer = new StatefulQueueConsumer(1, groupId, 2, groupName, hashKey, config);
    queue.configure(consumer0); // we must configure with instance #0

    // dequeue 15 -> 1 .. 29
    t = oracle.startTransaction();
    DequeueResult result = queue.dequeue(consumer, t.getReadPointer());
    oracle.commitTransaction(t);
    assertFalse(result.isEmpty());
    entries = result.getEntries();
    assertEquals(15, entries.length);
    for (int i = 0; i < 15; i++) {
      assertEquals(2 * i + 1, Bytes.toInt(entries[i].getData()));
    }

    // dequeue another 15 -> 31 .. 59
    t = oracle.startTransaction();
    result = queue.dequeue(consumer, t.getReadPointer());
    oracle.commitTransaction(t);
    assertFalse(result.isEmpty());
    entries = result.getEntries();
    assertEquals(15, entries.length);
    for (int i = 0; i < 15; i++) {
      assertEquals(2 * i + 31, Bytes.toInt(entries[i].getData()));
    }

    // ack second 15 + finalize
    t = oracle.startTransaction();
    QueueEntryPointer[] pointers = result.getEntryPointers();
    queue.ack(pointers, consumer, t.getReadPointer());
    queue.finalize(pointers, consumer, 2, t.getWriteVersion());
    oracle.commitTransaction(t);

    // ack second 15 again -> error
    t = oracle.startTransaction();
    try {
      queue.ack(pointers, consumer, t.getReadPointer());
      fail("acking for the second time should fail");
    } catch (OperationException e) {
      // expect ILLEGAL_ACK
      if (e.getStatus() != StatusCode.ILLEGAL_ACK) {
        throw e;
      }
    }
    oracle.commitTransaction(t);

    // dequeue another 15 -> 61 .. 89
    t = oracle.startTransaction();
    result = queue.dequeue(consumer, t.getReadPointer());
    oracle.commitTransaction(t);
    assertFalse(result.isEmpty());
    entries = result.getEntries();
    assertEquals(15, entries.length);
    for (int i = 0; i < 15; i++) {
      assertEquals(2 * i + 61, Bytes.toInt(entries[i].getData()));
    }

    // ack first 10
    t = oracle.startTransaction();
    pointers = Arrays.copyOf(result.getEntryPointers(), 10);
    queue.ack(pointers, consumer, t.getReadPointer());
    queue.finalize(pointers, consumer, 2, t.getWriteVersion());
    oracle.commitTransaction(t);

    // start new consumer (simulate crash)
    config = new QueueConfig(partitioner, false, 10, true);
    consumer = new StatefulQueueConsumer(1, groupId, 2, groupName, hashKey, config);
    queue.configure(consumer);

    // dequeue 10 -> 1 .. 19
    t = oracle.startTransaction();
    result = queue.dequeue(consumer, t.getReadPointer());
    oracle.commitTransaction(t);
    assertFalse(result.isEmpty());
    entries = result.getEntries();
    assertEquals(10, entries.length);
    for (int i = 0; i < 10; i++) {
      assertEquals(2 * i + 1, Bytes.toInt(entries[i].getData()));
    }
    QueueEntryPointer[] pointers1 = result.getEntryPointers();

    // dequeue 10 -> 21 .. 29, 81 ... 89
    t = oracle.startTransaction();
    result = queue.dequeue(consumer, t.getReadPointer());
    oracle.commitTransaction(t);
    assertFalse(result.isEmpty());
    entries = result.getEntries();
    assertEquals(10, entries.length);
    for (int i = 0; i < 5; i++) {
      assertEquals(2 * i + 21, Bytes.toInt(entries[i].getData()));
    }
    for (int i = 5; i < 10; i++) {
      assertEquals(2 * i + 71, Bytes.toInt(entries[i].getData()));
    }
    QueueEntryPointer[] pointers2 = result.getEntryPointers();

    // dequeue 10 -> 91 .. 99
    t = oracle.startTransaction();
    result = queue.dequeue(consumer, t.getReadPointer());
    oracle.commitTransaction(t);
    assertFalse(result.isEmpty());
    entries = result.getEntries();
    assertEquals(5, entries.length);
    for (int i = 0; i < 5; i++) {
      assertEquals(2 * i + 91, Bytes.toInt(entries[i].getData()));
    }
    QueueEntryPointer[] pointers3 = result.getEntryPointers();

    // dequeue 10 -> empty
    t = oracle.startTransaction();
    result = queue.dequeue(consumer, t.getReadPointer());
    oracle.commitTransaction(t);
    assertTrue(result.isEmpty());

    // ack 1 .. 29
    t = oracle.startTransaction();
    pointers = Arrays.copyOf(pointers1, pointers1.length + 5);
    System.arraycopy(pointers2, 0, pointers, pointers1.length, 5);
    queue.ack(pointers, consumer, t.getReadPointer());
    queue.finalize(pointers, consumer, 2, t.getWriteVersion());
    oracle.commitTransaction(t);

    // ack 81 .. 99
    t = oracle.startTransaction();
    // note pointers will be out of order
    pointers = Arrays.copyOf(pointers3, pointers3.length + 5);
    System.arraycopy(pointers2, 5, pointers, pointers3.length, 5);
    queue.ack(pointers, consumer, t.getReadPointer());
    queue.finalize(pointers, consumer, 2, t.getWriteVersion());
    oracle.commitTransaction(t);

    // new consumer
    // start new consumer (simulate crash)
    config = new QueueConfig(partitioner, false, 20, true);
    consumer = new StatefulQueueConsumer(1, groupId, 2, groupName, hashKey, config);
    queue.configure(consumer);

    // dequeue -> empty
    t = oracle.startTransaction();
    result = queue.dequeue(consumer, t.getReadPointer());
    oracle.commitTransaction(t);
    assertTrue(result.isEmpty());
  }

  @Test
  public void testBatchAsyncFifo() throws OperationException {

    TTQueue queue = createQueue();

    // enqueue 100
    QueueEntry[] entries = new QueueEntry[100];
    for (int i = 1; i <= entries.length; i++) {
      entries[i - 1] = new QueueEntry(Bytes.toBytes(i));
    }
    Transaction t = oracle.startTransaction();
    queue.enqueue(entries, t.getWriteVersion());
    oracle.commitTransaction(t);

    // we will dequeue with the given partitioning, two consumers in group, hence returns every other entry
    long groupId = 42;
    String groupName = "group";
    QueueConfig config = new QueueConfig(FIFO, false, 15, true);
    QueueConsumer consumer1 = new StatefulQueueConsumer(0, groupId, 2, groupName, config);
    QueueConsumer consumer2 = new StatefulQueueConsumer(1, groupId, 2, groupName, config);
    queue.configure(consumer1);
    queue.configure(consumer2);

    // dequeue 15 with first consumer -> 1 .. 15
    t = oracle.startTransaction();
    DequeueResult result1 = queue.dequeue(consumer1, t.getReadPointer());
    oracle.commitTransaction(t);
    assertFalse(result1.isEmpty());
    entries = result1.getEntries();
    assertEquals(15, entries.length);
    for (int i = 0; i < 15; i++) {
      assertEquals(i + 1, Bytes.toInt(entries[i].getData()));
    }

    // dequeue another 15 -> 16 .. 30
    t = oracle.startTransaction();
    result1 = queue.dequeue(consumer1, t.getReadPointer());
    oracle.commitTransaction(t);
    assertFalse(result1.isEmpty());
    entries = result1.getEntries();
    assertEquals(15, entries.length);
    for (int i = 0; i < 15; i++) {
      assertEquals(i + 16, Bytes.toInt(entries[i].getData()));
    }

    // dequeue 15 with second consumer -> 31 .. 45
    t = oracle.startTransaction();
    DequeueResult result2 = queue.dequeue(consumer2, t.getReadPointer());
    oracle.commitTransaction(t);
    assertFalse(result1.isEmpty());
    entries = result2.getEntries();
    assertEquals(15, entries.length);
    for (int i = 0; i < 15; i++) {
      assertEquals(i + 31, Bytes.toInt(entries[i].getData()));
    }

    // ack second batch with second consumer -> error
    t = oracle.startTransaction();
    QueueEntryPointer[] pointers = result1.getEntryPointers();
    try {
      queue.ack(pointers, consumer2, t.getReadPointer());
      fail("acking with wrong consumer should fail");
    } catch (OperationException e) {
      // expect ILLEGAL_ACK
      if (e.getStatus() != StatusCode.ILLEGAL_ACK) {
        throw e;
      }
    }
    oracle.commitTransaction(t);

    // ack second batch with first consumer -> ok
    t = oracle.startTransaction();
    queue.ack(pointers, consumer1, t.getReadPointer());
    queue.finalize(pointers, consumer1, 2, t.getWriteVersion());
    oracle.commitTransaction(t);

    // ack second batch again -> error
    t = oracle.startTransaction();
    try {
      queue.ack(pointers, consumer1, t.getReadPointer());
      fail("acking for the second time should fail");
    } catch (OperationException e) {
      // expect ILLEGAL_ACK
      if (e.getStatus() != StatusCode.ILLEGAL_ACK) {
        throw e;
      }
    }
    oracle.commitTransaction(t);

    // simulate crash of consumer 1 - new consumer with batch size 10
    config = new QueueConfig(FIFO, false, 10, true);
    consumer1 = new StatefulQueueConsumer(0, groupId, 2, groupName, config);

    // dequeue 10 with first consumer 1 .. 10
    t = oracle.startTransaction();
    result1 = queue.dequeue(consumer1, t.getReadPointer());
    oracle.commitTransaction(t);
    assertFalse(result1.isEmpty());
    entries = result1.getEntries();
    assertEquals(10, entries.length);
    for (int i = 0; i < 10; i++) {
      assertEquals(i + 1, Bytes.toInt(entries[i].getData()));
    }

    // ack these 10
    t = oracle.startTransaction();
    pointers = result1.getEntryPointers();
    queue.ack(pointers, consumer1, t.getReadPointer());
    queue.finalize(pointers, consumer1, 2, t.getWriteVersion());
    oracle.commitTransaction(t);

    // dequeue 10 with first consumer 11 .. 15
    t = oracle.startTransaction();
    result1 = queue.dequeue(consumer1, t.getReadPointer());
    oracle.commitTransaction(t);
    assertFalse(result1.isEmpty());
    entries = result1.getEntries();
    assertEquals(5, entries.length);
    for (int i = 0; i < 5; i++) {
      assertEquals(i + 11, Bytes.toInt(entries[i].getData()));
    }
    pointers = Arrays.copyOf(result1.getEntryPointers(), 15);

    // dequeue 10 with first consumer 46 .. 55
    t = oracle.startTransaction();
    result1 = queue.dequeue(consumer1, t.getReadPointer());
    oracle.commitTransaction(t);
    assertFalse(result1.isEmpty());
    entries = result1.getEntries();
    assertEquals(10, entries.length);
    for (int i = 0; i < 10; i++) {
      assertEquals(i + 46, Bytes.toInt(entries[i].getData()));
    }
    System.arraycopy(result1.getEntryPointers(), 0, pointers, 5, 10);

    // dequeue 15 with second consumer 56 .. 70
    t = oracle.startTransaction();
    result2 = queue.dequeue(consumer2, t.getReadPointer());
    oracle.commitTransaction(t);
    assertFalse(result2.isEmpty());
    entries = result2.getEntries();
    assertEquals(15, entries.length);
    for (int i = 0; i < 15; i++) {
      assertEquals(i + 56, Bytes.toInt(entries[i].getData()));
    }

    // ack previous 15 from first consumer at once
    t = oracle.startTransaction();
    queue.ack(pointers, consumer1, t.getReadPointer());
    queue.finalize(pointers, consumer1, 2, t.getWriteVersion());
    oracle.commitTransaction(t);

    pointers = Arrays.copyOf(result2.getEntryPointers(), 30);
    // dequeue 15 with second consumer 71 .. 85
    t = oracle.startTransaction();
    result2 = queue.dequeue(consumer2, t.getReadPointer());
    oracle.commitTransaction(t);
    assertFalse(result2.isEmpty());
    entries = result2.getEntries();
    assertEquals(15, entries.length);
    for (int i = 0; i < 15; i++) {
      assertEquals(i + 71, Bytes.toInt(entries[i].getData()));
    }

    // ack last two batches with second consumer
    System.arraycopy(result2.getEntryPointers(), 0, pointers, 15, 15);
    t = oracle.startTransaction();
    queue.ack(pointers, consumer2, t.getReadPointer());
    queue.finalize(pointers, consumer2, 2, t.getWriteVersion());
    oracle.commitTransaction(t);

    // dequeue 10 with first consumer 86 .. ?, less than 10 (only 15 left in queue)
    t = oracle.startTransaction();
    result1 = queue.dequeue(consumer1, t.getReadPointer());
    oracle.commitTransaction(t);
    assertFalse(result1.isEmpty());
    entries = result1.getEntries();
    assertTrue(1 < entries.length && entries.length < 10);
    for (int i = 0; i < entries.length; i++) {
      assertEquals(i + 86, Bytes.toInt(entries[i].getData()));
    }
    int dequeued = entries.length;

    // deqeuee 15 with second consumer ? .. ?, less than 15
    t = oracle.startTransaction();
    result2 = queue.dequeue(consumer2, t.getReadPointer());
    oracle.commitTransaction(t);
    assertFalse(result2.isEmpty());
    entries = result2.getEntries();
    assertTrue(1 < entries.length && entries.length < 15);
    for (int i = 0; i < entries.length; i++) {
      assertEquals(i + 86 + dequeued, Bytes.toInt(entries[i].getData()));
    }
    dequeued += entries.length;

    // enqueue another 30
    entries = new QueueEntry[30];
    for (int i = 1; i <= entries.length; i++) {
      entries[i - 1] = new QueueEntry(Bytes.toBytes(i + 100));
    }
    t = oracle.startTransaction();
    queue.enqueue(entries, t.getWriteVersion());
    oracle.commitTransaction(t);

    // dequeue 15 with second -> 15
    t = oracle.startTransaction();
    result2 = queue.dequeue(consumer2, t.getReadPointer());
    oracle.commitTransaction(t);
    assertFalse(result2.isEmpty());
    entries = result2.getEntries();
    assertEquals(15, entries.length);
    for (int i = 0; i < entries.length; i++) {
      assertEquals(i + 86 + dequeued, Bytes.toInt(entries[i].getData()));
    }
  }

  // this tests that the dequeue can deal with a batch of consecutive, invalid queue entries that is larger than the
  // dequeue batch size. If it does not deal with this condition correctly, it may return empty or even go into an
  // infinite loop!
  @Test//(timeout = 10000)
  public void testSkipBatchofInvalidEntries () throws OperationException {
    TTQueue queue = createQueue();

    // enqueue 20, then invalidate a batch of 10 in the middle
    QueueEntry[] entries = new QueueEntry[20];
    for (int i = 1; i <= entries.length; i++) {
      entries[i - 1] = new QueueEntry(Bytes.toBytes(i));
      entries[i - 1].addHashKey("p", i);
    }
    Transaction t = oracle.startTransaction();
    EnqueueResult enqResult = queue.enqueue(entries, t.getWriteVersion());
    queue.invalidate(Arrays.copyOfRange(enqResult.getEntryPointers(), 5, 15), t.getWriteVersion());
    oracle.commitTransaction(t);

    long groupId = 0;
    for (int batchSize = 1; batchSize < 12; batchSize++) {
      for (boolean returnBatch : new boolean[] { true, false }) {
        for (boolean singleEntry : new boolean[] { true, false }) {
          for (PartitionerType partitioner : new PartitionerType[] { FIFO, ROUND_ROBIN, HASH }) {
            testSkipBatchofInvalidEntries(queue, ++groupId, batchSize, returnBatch, singleEntry, partitioner);
          }
        }
      }
    }
  }

  public void testSkipBatchofInvalidEntries(TTQueue queue, long groupId, int batchSize, boolean returnBatch,
                                            boolean singleEntry, PartitionerType partitioner)
    throws OperationException {

    String groupName = "batch=" + batchSize + ":" + returnBatch + "," + (singleEntry ? "single" : "multi")
      + "," + partitioner;
    // System.out.println(groupName);
    QueueConfig config = new QueueConfig(partitioner, singleEntry, batchSize, returnBatch);
    QueueConsumer consumer = new StatefulQueueConsumer(0, groupId, 2, groupName, "p", config);
    queue.configure(consumer);

    int numDequeued = 0;
    while (true) {
      Transaction t = oracle.startTransaction();
      DequeueResult result = queue.dequeue(consumer, t.getReadPointer());
      oracle.commitTransaction(t);
      if (result.isEmpty()) {
        break;
      }
      numDequeued += result.getEntries().length;
      t = oracle.startTransaction();
      queue.ack(result.getEntryPointers(), consumer, t.getReadPointer());
      queue.finalize(result.getEntryPointers(), consumer, 1, t.getWriteVersion());
      oracle.commitTransaction(t);
    }
    int expected = partitioner == FIFO ? 10 : 5;
    assertEquals("Failure for " + groupName + ":", expected, numDequeued);
  }

  // tests that if we use hash partitioning, we can also consume entries without hash key
  @Test
  public void testHashWithoutHashKey() throws OperationException {
    TTQueue queue = createQueue();

    // enqueue 20, then invalidate a batch of 10 in the middle
    QueueEntry[] entries = new QueueEntry[20];
    for (int i = 1; i <= entries.length; i++) {
      entries[i - 1] = new QueueEntry(Bytes.toBytes(i));
    }
    Transaction t = oracle.startTransaction();
    EnqueueResult enqResult = queue.enqueue(entries, t.getWriteVersion());
    oracle.commitTransaction(t);

    QueueConfig config = new QueueConfig(HASH, false, 4, true);
    QueueConsumer[] consumers = {
      new StatefulQueueConsumer(0, 17, 2, "or'ly", "non-existent-hash-key", config),
      new StatefulQueueConsumer(1, 17, 2, "or'ly", "non-existent-hash-key", config) };
    queue.configure(consumers[0]);

    int numDequeued = 0;
    boolean allEmpty;
    do {
      allEmpty = true;
      for (QueueConsumer consumer : consumers) {
        t = oracle.startTransaction();
        DequeueResult result = queue.dequeue(consumer, t.getReadPointer());
        oracle.commitTransaction(t);
        if (result.isEmpty()) {
          continue;
        }
        allEmpty = false;
        numDequeued += result.getEntries().length;
        t = oracle.startTransaction();
        queue.ack(result.getEntryPointers(), consumer, t.getReadPointer());
        queue.finalize(result.getEntryPointers(), consumer, 1, t.getWriteVersion());
        oracle.commitTransaction(t);
      }
    } while (!allEmpty);

    assertEquals(20, numDequeued);
  }
}
