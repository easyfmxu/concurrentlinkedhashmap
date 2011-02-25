package com.googlecode.concurrentlinkedhashmap;

import static com.googlecode.concurrentlinkedhashmap.ConcurrentLinkedHashMap.MAXIMUM_CAPACITY;
import static com.googlecode.concurrentlinkedhashmap.ConcurrentLinkedHashMap.RECENCY_THRESHOLD;
import static com.googlecode.concurrentlinkedhashmap.IsValidState.valid;
import static java.util.Arrays.asList;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;

import com.googlecode.concurrentlinkedhashmap.ConcurrentLinkedHashMap.Builder;
import com.googlecode.concurrentlinkedhashmap.ConcurrentLinkedHashMap.LruNode;
import com.googlecode.concurrentlinkedhashmap.ConcurrentLinkedHashMap.RecencyReference;

import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * A unit-test for the page replacement algorithm and its public methods.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
@Test(groups = "development")
public final class EvictionTest extends BaseTest {

  @Override
  protected int capacity() {
    return 100;
  }

  @Test(dataProvider = "warmedMap")
  public void capacity_increase(ConcurrentLinkedHashMap<Integer, Integer> map) {
    Map<Integer, Integer> expected = ImmutableMap.copyOf(newWarmedMap());
    int newMaxCapacity = 2 * capacity();

    map.setCapacity(newMaxCapacity);
    assertThat(map, is(equalTo(expected)));
    assertThat(map.capacity(), is(equalTo(newMaxCapacity)));
  }

  @Test(dataProvider = "warmedMap")
  public void capacity_increaseToMaximum(ConcurrentLinkedHashMap<Integer, Integer> map) {
    map.setCapacity(MAXIMUM_CAPACITY);
    assertThat(map.capacity(), is(equalTo(MAXIMUM_CAPACITY)));
  }

  @Test(dataProvider = "warmedMap")
  public void capacity_increaseAboveMaximum(ConcurrentLinkedHashMap<Integer, Integer> map) {
    map.setCapacity(MAXIMUM_CAPACITY + 1);
    assertThat(map.capacity(), is(equalTo(MAXIMUM_CAPACITY)));
  }

  @Test(dataProvider = "singletonWeigher")
  public void capacity_decrease(Weigher<Integer> weigher) {
    checkDecreasedCapacity(weigher, capacity() / 2);
  }

  @Test(dataProvider = "singletonWeigher")
  public void capacity_decreaseToMinimum(Weigher<Integer> weigher) {
    checkDecreasedCapacity(weigher, 0);
  }

  private void checkDecreasedCapacity(Weigher<Integer> weigher, int newMaxCapacity) {
    CollectingListener<Integer, Integer> listener = new CollectingListener<Integer, Integer>();
    ConcurrentLinkedHashMap<Integer, Integer> map = new Builder<Integer, Integer>()
        .maximumWeightedCapacity(capacity())
        .listener(listener)
        .weigher(weigher)
        .build();
    warmUp(map, 0, capacity());
    map.setCapacity(newMaxCapacity);

    assertThat(map, is(valid()));
    assertThat(map.size(), is(equalTo(newMaxCapacity)));
    assertThat(map.capacity(), is(equalTo(newMaxCapacity)));
    assertThat(listener.evicted, hasSize(capacity() - newMaxCapacity));
  }

  @Test(dataProvider = "warmedMap", expectedExceptions = IllegalArgumentException.class)
  public void capacity_decreaseBelowMinimum(ConcurrentLinkedHashMap<Integer, Integer> map) {
    try {
      map.setCapacity(-1);
    } finally {
      assertThat(map.capacity(), is(equalTo(capacity())));
    }
  }

  @Test(dataProvider = "builder", expectedExceptions = IllegalStateException.class)
  public void evictionListener_fails(Builder<Integer, Integer> builder) {
    ConcurrentLinkedHashMap<Integer, Integer> map = builder
        .listener(new EvictionListener<Integer, Integer>() {
          @Override public void onEviction(Integer key, Integer value) {
            throw new IllegalStateException();
          }
        })
        .maximumWeightedCapacity(0)
        .build();
    try {
      warmUp(map, 0, capacity());
    } finally {
      assertThat(map, is(valid()));
    }
  }

  @Test(dataProvider = "collectingListener")
  public void evict_alwaysDiscard(CollectingListener<Integer, Integer> listener) {
    ConcurrentLinkedHashMap<Integer, Integer> map = new Builder<Integer, Integer>()
        .maximumWeightedCapacity(0)
        .listener(listener)
        .build();
    warmUp(map, 0, 100);

    assertThat(map, is(valid()));
    assertThat(listener.evicted, hasSize(100));
  }

  @Test(dataProvider = "collectingListener")
  public void evict(CollectingListener<Integer, Integer> listener) {
    ConcurrentLinkedHashMap<Integer, Integer> map = new Builder<Integer, Integer>()
        .maximumWeightedCapacity(10)
        .listener(listener)
        .build();
    warmUp(map, 0, 20);

    assertThat(map, is(valid()));
    assertThat(map.size(), is(10));
    assertThat(map.weightedSize(), is(10));
    assertThat(listener.evicted, hasSize(10));
  }

  @Test(dataProvider = "builder")
  public void evict_weighted(Builder<Integer, Collection<Integer>> builder) {
    ConcurrentLinkedHashMap<Integer, Collection<Integer>> map = builder
        .weigher(Weighers.<Integer>collection())
        .maximumWeightedCapacity(10)
        .build();

    map.put(1, asList(1, 2));
    map.put(2, asList(3, 4, 5, 6, 7));
    map.put(3, asList(8, 9, 10));
    assertThat(map.weightedSize(), is(10));

    // evict (1)
    map.put(4, asList(11));
    assertThat(map.containsKey(1), is(false));
    assertThat(map.weightedSize(), is(9));

    // evict (2, 3)
    map.put(5, asList(12, 13, 14, 15, 16, 17, 18, 19, 20));
    assertThat(map.weightedSize(), is(10));

    assertThat(map, is(valid()));
  }

  @Test(dataProvider = "builder")
  public void evict_lru(Builder<Object, Object> builder) {
    ConcurrentLinkedHashMap<Object, Object> map = builder
        .weigher(newCustomSingletonWeigher())
        .maximumWeightedCapacity(10)
        .build();
    warmUp(map, 0, 10);
    checkContainsInOrder(map, 0, 1, 2, 3, 4, 5, 6, 7, 8, 9);

    // re-order
    checkReorder(map, asList(0, 1, 2), 3, 4, 5, 6, 7, 8, 9, 0, 1, 2);

    // evict 3, 4, 5
    checkEvict(map, asList(10, 11, 12), 6, 7, 8, 9, 0, 1, 2, 10, 11, 12);

    // re-order
    checkReorder(map, asList(6, 7, 8), 9, 0, 1, 2, 10, 11, 12, 6, 7, 8);

    // evict 9, 0, 1
    checkEvict(map, asList(13, 14, 15), 2, 10, 11, 12, 6, 7, 8, 13, 14, 15);

    assertThat(map, is(valid()));
  }

  private void checkReorder(ConcurrentLinkedHashMap<?, ?> map,
      List<Integer> keys, Object... expect) {
    for (int i : keys) {
      map.get(i);
    }
    checkContainsInOrder(map, expect);
  }

  private void checkEvict(ConcurrentLinkedHashMap<Object, Object> map,
      List<Integer> keys, Object... expect) {
    for (int i : keys) {
      map.put(i, i);
    }
    checkContainsInOrder(map, expect);
  }

  private void checkContainsInOrder(ConcurrentLinkedHashMap<?, ?> map,
      Object... expect) {
    map.tryToDrainEvictionQueues(false);
    List<Object> evictionList = Lists.newArrayList();
    @SuppressWarnings("unchecked")
    LruNode current = asLruNode(map.sentinel).next;
    while (current != map.sentinel) {
      evictionList.add(current.key);
      current = current.next;
    }
    assertThat(map.size(), is(equalTo(expect.length)));
    assertThat(map.keySet(), containsInAnyOrder(expect));
    assertThat(evictionList, is(equalTo(asList(expect))));
  }

  @Test(dataProvider = "warmedMap")
  public void updateRecency_onGet(final ConcurrentLinkedHashMap<?, ?> map) {
    final ConcurrentLinkedHashMap<?, ?>.LruNode originalHead = asLruNode(map.sentinel).next;
    updateRecency(map, new Runnable() {
      @Override public void run() {
        map.get(originalHead.key);
      }
    });
  }

  @Test(dataProvider = "warmedMap")
  public void updateRecency_onPutIfAbsent(final ConcurrentLinkedHashMap<Object, Object> map) {
    @SuppressWarnings("unchecked")
    final LruNode originalHead = asLruNode(map.sentinel).next;
    updateRecency(map, new Runnable() {
      @Override public void run() {
        map.putIfAbsent(originalHead.key, originalHead.key);
      }
    });
  }

  @Test(dataProvider = "warmedMap")
  public void updateRecency_onPut(final ConcurrentLinkedHashMap<Object, Object> map) {
    @SuppressWarnings("unchecked")
    final LruNode originalHead = asLruNode(map.sentinel).next;
    updateRecency(map, new Runnable() {
      @Override public void run() {
        map.put(originalHead.key, originalHead.key);
      }
    });
  }

  @Test(dataProvider = "warmedMap")
  public void updateRecency_onReplace(final ConcurrentLinkedHashMap<Object, Object> map) {
    @SuppressWarnings("unchecked")
    final LruNode originalHead = asLruNode(map.sentinel).next;
    updateRecency(map, new Runnable() {
      @Override public void run() {
        map.replace(originalHead.key, originalHead.key);
      }
    });
  }

  @Test(dataProvider = "warmedMap")
  public void updateRecency_onReplaceConditionally(
      final ConcurrentLinkedHashMap<Object, Object> map) {
    @SuppressWarnings("unchecked")
    final LruNode originalHead = asLruNode(map.sentinel).next;
    updateRecency(map, new Runnable() {
      @Override public void run() {
        map.replace(originalHead.key, originalHead.key, originalHead.key);
      }
    });
  }

  @SuppressWarnings("unchecked")
  private void updateRecency(ConcurrentLinkedHashMap<?, ?> map, Runnable operation) {
    LruNode sentinel = asLruNode(map.sentinel);
    LruNode originalHead = sentinel.next;

    operation.run();
    map.drainRecencyQueues();

    assertThat(sentinel.next, is(not(originalHead)));
    assertThat(sentinel.prev, is(originalHead));
    assertThat(map, is(valid()));
  }

  @Test(dataProvider = "warmedMap")
  public void drainRecencyQueue(ConcurrentLinkedHashMap<Integer, Integer> map) {
    for (int i = 0; i < RECENCY_THRESHOLD; i++) {
      map.get(1);
    }
    int index = (int) Thread.currentThread().getId() % map.recencyQueue.length;
    assertThat(map.recencyQueueLength.get(index), is(equalTo(RECENCY_THRESHOLD)));
    map.get(1);
    assertThat(map.recencyQueueLength.get(index), is(equalTo(0)));
  }

  @Test(dataProvider = "guardedMap")
  public void applyInRecencyOrder(ConcurrentLinkedHashMap<Integer, Integer> map) {
    // Add a dummy set of recency operations to the queues
    for (int i = 0; i < map.maxRecenciesToDrain; i++) {
      map.addToRecencyQueue(null);
    }

    // Perform the merging in the same manner as when draining the queues
    Object[] recencies = new Object[map.maxRecenciesToDrain];
    List<ConcurrentLinkedHashMap<Integer, Integer>.RecencyReference> overflow =
      new ArrayList<ConcurrentLinkedHashMap<Integer, Integer>.RecencyReference>();
    int lastRecencyIndex = map.moveRecenciesFromQueues(recencies, overflow);

    // Check that the recencies are in sorted order
    Integer last = null;
    for (int i = 0; i <= lastRecencyIndex; i++) {
      @SuppressWarnings("unchecked")
      int order = ((RecencyReference) recencies[i]).recencyOrder;
      if (last != null) {
        assertThat(order, is(equalTo(last + 1)));
      }
    }
    for (int i = lastRecencyIndex + 1; i < recencies.length; i++) {
      assertThat(recencies[i], is(nullValue()));
    }
    assertThat(overflow, hasSize(0));
  }
}
