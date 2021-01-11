# ConcurrentHashMap底层实现原理

在并发编程中使用HashMap可能导致程序死循环。而使用线程安全的HashTable效率又非常低下，基于以上两个原因，便有了ConcurrentHashMap的登场机会。



## 使用ConcurrentHashMap的原因

### 1）线程不安全的HashMap

多线程往HashMap中put数据会在HashMap中形成环，一旦形成环，在从HashMap中get数据时，由于Entry的next节点永远不为空，就会产生死循环获取Entry。JDK 8已经不会形成环，但是还是存在数据丢失的风险，所以在多线程环境下不建议使用HashMap

### 2）效率低下的HashTable

HashTable容器使用synchronized来保证线程安全，但在线程竞争激烈的情况下HashTable的效率非常低下。因为当一个线程访问HashTable的同步方法，其他线程也访问HashTable的同步方法时，会进入阻塞或轮询状态。如线程1使用put进行元素添加，线程2不但不能使用put方法添加元素，也不能使用get方法来获取元素，所以竞争越激烈效率越低。

### 3）ConcurrentHashMap使用锁分段技术有效提高并发访问率

HashTable效率低下的原因是所有线程都竞争同一把锁。JDK 1.7中ConcurrentHashMap使用**锁分段技术**，将数据分成一段一段地存储，然后给每一段数据配一把锁，当一个线程占用锁访问其中一段数据时候，其他段数据也能被其他线程访问。JDK 1.8摒弃了Segment的概念，直接使用volatile Node数组+链表+红黑树的结构来实现，并发控制使用Synchronized和CAS来操作，看起来就像优化过且线程安全的HashMap。

------

下面的内容基于JDK1.8中对ConcurrentHashMap的实现。

## ConcurrentHashMap使用的数据结构

ConcurrentHashMap中使用一个volatile Node类型的数组来存储所有数据，数组定义源码如下：

```java
/**
     * The array of bins. Lazily initialized upon first insertion.
     * Size is always a power of two. Accessed directly by iterators.
     */
transient volatile Node<K,V>[] table;
```

table数组在第一次插入数据时才会被初始化，数组的容量总是2的幂，这是因为在计算node的插入位置时，要用以key值计算出的hash码和数组长度-1进行与操作，如果数组长度不是2的幂，长度-1就会出现0位i，详细内容可以参考：[HashMap底层实现原理](https://xinxingastro.github.io/2018/05/11/Java/HashMap底层实现原理/)。

Node类型源码如下所示：

```java
/**
     * Key-value entry.  This class is never exported out as a
     * user-mutable Map.Entry (i.e., one supporting setValue; see
     * MapEntry below), but can be used for read-only traversals used
     * in bulk tasks.  Subclasses of Node with a negative hash field
     * are special, and contain null keys and values (but are never
     * exported).  Otherwise, keys and vals are never null.
     */
static class Node<K,V> implements Map.Entry<K,V> {
    final int hash;
    final K key;
    volatile V val;
    volatile Node<K,V> next;

    Node(int hash, K key, V val, Node<K,V> next) {
        this.hash = hash;
        this.key = key;
        this.val = val;
        this.next = next;
    }

    public final K getKey()       { return key; }
    public final V getValue()     { return val; }
    public final int hashCode()   { return key.hashCode() ^ val.hashCode(); }
    public final String toString(){ return key + "=" + val; }
    // 这里setValue时会直接抛出异常，所以不允许更新值
    public final V setValue(V value) {
        throw new UnsupportedOperationException();
    }

    public final boolean equals(Object o) {
        Object k, v, u; Map.Entry<?,?> e;
        return ((o instanceof Map.Entry) &&
                (k = (e = (Map.Entry<?,?>)o).getKey()) != null &&
                (v = e.getValue()) != null &&
                (k == key || k.equals(key)) &&
                (v == (u = val) || v.equals(u)));
    }

    /**
         * Virtualized support for map.get(); overridden in subclasses.
         */
    Node<K,V> find(int h, Object k) {
        Node<K,V> e = this;
        if (k != null) {
            do {
                K ek;
                if (e.hash == h &&
                    ((ek = e.key) == k || (ek != null && k.equals(ek))))
                    return e;
            } while ((e = e.next) != null);
        }
        return null;
    }
}
```

如果出现hash冲突，系统就在冲突位置的形成一个链表，这个链表在JDK中叫做Bin，由以上源码可知，Node就是一个单链表的节点，这种节点只允许对数据进行查询，不允许修改。

如果一个Bin中的节点个数超过8，系统就会自动将这个bin转换成红黑树。TREEIFY-THRESHOLD定义了转换的阀值。

```java
/**
     * The bin count threshold for using a tree rather than list for a
     * bin.  Bins are converted to trees when adding an element to a
     * bin with at least this many nodes. The value must be greater
     * than 2, and should be at least 8 to mesh with assumptions in
     * tree removal about conversion back to plain bins upon
     * shrinkage.
     */
static final int TREEIFY_THRESHOLD = 8;
```

TreeNode就是红黑树中的节点类型，TreeNode源码如下：

由于TreeNode是继承自Node类的所以Node中所有属性TreeNode中都有。在使用时可以把一个TreeNode就当作一个Node使用。

```java
/**
 * Nodes for use in TreeBins
 */
static final class TreeNode<K,V> extends Node<K,V> {
    TreeNode<K,V> parent;  // red-black tree links
    TreeNode<K,V> left;
    TreeNode<K,V> right;
    TreeNode<K,V> prev;    // needed to unlink next upon deletion
    boolean red;

    TreeNode(int hash, K key, V val, Node<K,V> next,
             TreeNode<K,V> parent) {
        super(hash, key, val, next);
        this.parent = parent;
    }

    Node<K,V> find(int h, Object k) {
        return findTreeNode(h, k, null);
    }

    /**
         * Returns the TreeNode (or null if not found) for the given key
         * starting at given root.
         */
    final TreeNode<K,V> findTreeNode(int h, Object k, Class<?> kc) {
        if (k != null) {
            TreeNode<K,V> p = this;
            do  {
                int ph, dir; K pk; TreeNode<K,V> q;
                TreeNode<K,V> pl = p.left, pr = p.right;
                if ((ph = p.hash) > h)
                    p = pl;
                else if (ph < h)
                    p = pr;
                else if ((pk = p.key) == k || (pk != null && k.equals(pk)))
                    return p;
                else if (pl == null)
                    p = pr;
                else if (pr == null)
                    p = pl;
                else if ((kc != null ||
                          (kc = comparableClassFor(k)) != null) &&
                         (dir = compareComparables(kc, k, pk)) != 0)
                    p = (dir < 0) ? pl : pr;
                else if ((q = pr.findTreeNode(h, k, kc)) != null)
                    return q;
                else
                    p = pl;
            } while (p != null);
        }
        return null;
    }
}
```

TreeBin是用来封装红黑树的容器，TreeBin的源码如下，在TreeBin中维护一个读写锁，一个线程如果想修改红黑树中的值必须拿到TreeBin的读写锁。

```java
/**
     * TreeNodes used at the heads of bins. TreeBins do not hold user
     * keys or values, but instead point to list of TreeNodes and
     * their root. They also maintain a parasitic read-write lock
     * forcing writers (who hold bin lock) to wait for readers (who do
     * not) to complete before tree restructuring operations.
     */
static final class TreeBin<K,V> extends Node<K,V> {
    TreeNode<K,V> root;
    volatile TreeNode<K,V> first;
    volatile Thread waiter;
    volatile int lockState;
    // values for lockState
    static final int WRITER = 1; // set while holding write lock
    static final int WAITER = 2; // set when waiting for write lock
    static final int READER = 4; // increment value for setting read lock

    /**
         * Tie-breaking utility for ordering insertions when equal
         * hashCodes and non-comparable. We don't require a total
         * order, just a consistent insertion rule to maintain
         * equivalence across rebalancings. Tie-breaking further than
         * necessary simplifies testing a bit.
         */
    static int tieBreakOrder(Object a, Object b) {
        int d;
        if (a == null || b == null ||
            (d = a.getClass().getName().
             compareTo(b.getClass().getName())) == 0)
            d = (System.identityHashCode(a) <= System.identityHashCode(b) ?
                 -1 : 1);
        return d;
    }

    /**
         * Creates bin with initial set of nodes headed by b.
         */
    TreeBin(TreeNode<K,V> b) {
        super(TREEBIN, null, null, null);
        this.first = b;
        TreeNode<K,V> r = null;
        for (TreeNode<K,V> x = b, next; x != null; x = next) {
            next = (TreeNode<K,V>)x.next;
            x.left = x.right = null;
            if (r == null) {
                x.parent = null;
                x.red = false;
                r = x;
            }
            else {
                K k = x.key;
                int h = x.hash;
                Class<?> kc = null;
                for (TreeNode<K,V> p = r;;) {
                    int dir, ph;
                    K pk = p.key;
                    if ((ph = p.hash) > h)
                        dir = -1;
                    else if (ph < h)
                        dir = 1;
                    else if ((kc == null &&
                              (kc = comparableClassFor(k)) == null) ||
                             (dir = compareComparables(kc, k, pk)) == 0)
                        dir = tieBreakOrder(k, pk);
                    TreeNode<K,V> xp = p;
                    if ((p = (dir <= 0) ? p.left : p.right) == null) {
                        x.parent = xp;
                        if (dir <= 0)
                            xp.left = x;
                        else
                            xp.right = x;
                        r = balanceInsertion(r, x);
                        break;
                    }
                }
            }
        }
        this.root = r;
        assert checkInvariants(root);
    }
```

## PUT操作

ConcurrentHashMap的初始化并不在构造函数中实现，而是在第一插入数据时，在put方法中实现。

put方法源码如下：

```java
public V put(K key, V value) {
    return putVal(key, value, false);
}
/** Implementation for put and putIfAbsent */
final V putVal(K key, V value, boolean onlyIfAbsent) {
    if (key == null || value == null) throw new NullPointerException();
    int hash = spread(key.hashCode());
    int binCount = 0;
    for (Node<K,V>[] tab = table;;) {
        Node<K,V> f; 
        int n, i, fh; //f表示插入位置节点，fh表示插入位置节点的hash值
        if (tab == null || (n = tab.length) == 0)
            tab = initTable();
        else if ((f = tabAt(tab, i = (n - 1) & hash)) == null) {
            if (casTabAt(tab, i, null,
                         new Node<K,V>(hash, key, value, null)))
                break;                   // no lock when adding to empty bin
        }
        else if ((fh = f.hash) == MOVED)
            tab = helpTransfer(tab, f);
        else {
            V oldVal = null;
            synchronized (f) {
                if (tabAt(tab, i) == f) {
                    if (fh >= 0) {
                        binCount = 1;
                        for (Node<K,V> e = f;; ++binCount) {
                            K ek;
                            if (e.hash == hash &&
                                ((ek = e.key) == key ||
                                 (ek != null && key.equals(ek)))) {
                                oldVal = e.val;
                                if (!onlyIfAbsent)
                                    e.val = value;
                                break;
                            }
                            Node<K,V> pred = e;
                            if ((e = e.next) == null) {
                                pred.next = new Node<K,V>(hash, key,
                                                          value, null);
                                break;
                            }
                        }
                    }
                    else if (f instanceof TreeBin) {
                        Node<K,V> p;
                        binCount = 2;
                        if ((p = ((TreeBin<K,V>)f).putTreeVal(hash, key,
                                                              value)) != null) {
                            oldVal = p.val;
                            if (!onlyIfAbsent)
                                p.val = value;
                        }
                    }
                }
            }
            if (binCount != 0) {
                if (binCount >= TREEIFY_THRESHOLD)
                    treeifyBin(tab, i);
                if (oldVal != null)
                    return oldVal;
                break;
            }
        }
    }
    addCount(1L, binCount);
    return null;
}
static final int spread(int h) {
    return (h ^ (h >>> 16)) & HASH_BITS;
}
```

put方法大致分以下几步：

1）如果key和value不为null，则计算hash值。和HashMap相同的是，ConcurrentHashMap也会对key的hashcode高16位和低16位进行异或，不同的是，ConcurrentHashMap中还会将异或出的结果跟一个HASH_BITS变量相与。HASH_BITS定义如下：

```java
static final int HASH_BITS = 0x7fffffff; // usable bits of normal node hash
```

HASH_BITS指定来可以用来计算hash的位数，默认值为31位。

2）如果发现table还没初始化，则调用iniTable()方法对table进行初始化。

3）计算插入位置使用`i = (n - 1) & hash)`，如果插入位置为空，则新建Node利用CAS操作插入节点，然后返回。

4）如果插入位置节点的hash值等于-1，则需要扩容，然后进行多线程并发扩容操作。

5）否则，说明存在hash冲突。首先锁定插入位置节点，如果插入位置节点的hash值>=0，则说明插入点是链表结构，新建Node插入。如果插入位置节点是TreeNode，则新建TreeNode插入。

在插入Node节点时使用一个for循环进行判断，如果当前节点的key值和插入节点相同，直接将该节点的value修改为插入节点的value（onlyIfAbsent属性可以设置只有插入位置空时才插入）。如果key值不相同则遍历链表继续判断。

6）检查当前Bin的容量，如果超过了阀值，则要将链表转化成红黑树。

从put流程中可以发现，JDK1.8处理冲突时使用的是乐观锁，当有冲突时才进行并发处理。

## GET操作

get方法的源码如下所示：

```java
public V get(Object key) {
    Node<K,V>[] tab; Node<K,V> e, p; int n, eh; K ek;
    int h = spread(key.hashCode());
    if ((tab = table) != null && (n = tab.length) > 0 &&
        (e = tabAt(tab, (n - 1) & h)) != null) {
        if ((eh = e.hash) == h) {
            if ((ek = e.key) == key || (ek != null && key.equals(ek)))
                return e.val;
        }
        else if (eh < 0)
            return (p = e.find(h, key)) != null ? p.val : null;
        while ((e = e.next) != null) {
            if (e.hash == h &&
                ((ek = e.key) == key || (ek != null && key.equals(ek))))
                return e.val;
        }
    }
    return null;
}
```

从上面的源码可以看到get的操作流程为：

计算key的hash值，定位到table索引位置，如果首节点符合就返回，如果不符合就循环链表，匹配就返回，不匹配就返回null。

## 总结

JDK1.8版本的ConcurrentHashMap的数据结构已经接近HashMap，相对而言，ConcurrentHashMap只是增加了同步的操作来控制并发，从JDK1.7版本的ReentrantLock+Segment+HashEntry，到JDK1.8版本中synchronized+CAS+HashEntry+红黑树，ConcurrentHashMap发生了如下变化：

1. JDK1.8中将锁的粒度进一步降低，更好的支持高并发场景。JDK1.7中锁住的是Segment对象，一个Segment中包含很多HashEntry。而JDK1.8中加锁的对象是插入点位置的节点。
2. JDK1.8中使用红黑树代替链表，提高了查找速度。
3. 由于锁粒度的降低，JDK1.8中使用Synchronized代替ReentrantLock，增加了灵活度，降低了系统开销。