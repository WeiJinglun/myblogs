# Redis源码学习
 
本页主要记录个人阅读 Redis 源码总结,版本 redis5.0.8

- redis 数据结构
    - [简单动态字符串 SDS](/redis/sds.md)
    - [跳跃表](/redis/zskiplist.md)
    - [压缩列表](/redis/ziplist.md)
    - [字典](/redis/dict.md)
    - [整数集合](/redis/intset.md)
    - [quicklist](/redis/quicklist.md)
    - [Stream](/redis/Stream.md)

- 面试题
  - [Redis 和 Memcached 有什么区别？Redis 的线程模型是什么？为什么单线程的 Redis 比多线程的 Memcached 效率要高得多？](/redis/redis-single-thread-model.md)
  - [Redis 都有哪些数据类型？分别在哪些场景下使用比较合适？](/redis/redis-data-types.md)
  - [Redis 的过期策略都有哪些？手写一下 LRU 代码实现？](/redis/redis-expiration-policies-and-lru.md)
  - [Redis 的持久化有哪几种方式？不同的持久化机制都有什么优缺点？持久化机制具体底层是如何实现的？](/redis/redis-persistence.md)
  - [Redis 集群模式的工作原理能说一下么？在集群模式下，Redis 的 key 是如何寻址的？分布式寻址都有哪些算法？了解一致性 hash 算法吗？如何动态增加和删除一个节点？](/redis/redis-cluster.md)
  - [了解什么是 redis 的雪崩、穿透和击穿？Redis 崩溃之后会怎么样？系统该如何应对这种情况？如何处理 Redis 的穿透？](/redis/redis-caching-avalanche-and-caching-penetration.md)
  - [如何保证缓存与数据库的双写一致性？](/redis/redis-consistence.md)
  - [Redis 的并发竞争问题是什么？如何解决这个问题？了解 Redis 事务的 CAS 方案吗？](/redis/redis-cas.md)
  - [生产环境中的 Redis 是怎么部署的？](/redis/redis-production-environment.md)
  - [有了解过 Redis rehash 的过程吗？](/redis/redis-rehash.md)
