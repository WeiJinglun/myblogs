# 20 | 幻读是什么，幻读有什么问题？

为了便于说明上一篇的问题，就先使用一个小一点儿的表。建表和初始化语句如下：

```sql
CREATE TABLE `t` (
  `id` int(11) NOT NULL,
  `c` int(11) DEFAULT NULL,
  `d` int(11) DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `c` (`c`)
) ENGINE=InnoDB;
 
insert into t values(0,0,0),(5,5,5),
(10,10,10),(15,15,15),(20,20,20),(25,25,25);
```

这个表除了主键 id 外，还有一个索引 c，初始化语句在表中插入了 6 行数据。

那么问题是，下面的语句序列，是怎么加锁的，加的锁又是什么时候释放的呢？

```sql
begin;
select * from t where d=5 for update;
commit;
```

比较好理解的是，这个语句会命中 d=5 的这一行，对应的主键 id=5，因此在 select 语句执行完成后，id=5 这一行会加一个写锁，而且由于两阶段锁协议，这个写锁会在执行 commit 语句的时候释放。

由于字段 d 上没有索引，因此这条查询语句会做全表扫描。那么，其他被扫描到的，但是不满足条件的 5 行记录上，会不会被加锁呢？

首先，InnoDB 的默认事务隔离级别是可重复读，所以接下来没有特殊说明的部分，都是设定在可重复读隔离级别下。

## 幻读是什么？

如果只在 id=5 这一行加锁，而其他行的不加锁的话，会怎么样。

下面先来假设一下这个场景：

|      | session A                                                    | session B                        | Session C                       |
| ---- | ------------------------------------------------------------ | -------------------------------- | ------------------------------- |
| T1   | begin;<br />select * from t where d = 5 for update;/\*Q1\*/<br /><font color='red'>result: (5, 5, 5)</font> |                                  |                                 |
| T2   |                                                              | update t set d = 5 where id = 0; |                                 |
| T3   | select * from t where d = 5 for update;/\*Q2\*/<br /><font color='red'>result: (0, 0, 5), (5, 5, 5)</font> |                                  |                                 |
| T4   |                                                              |                                  | insert into t values (1, 1, 5); |
| T5   | select * from t where d = 5 for update;/\*Q3\*/<br /><font color='red'>result: (0, 0, 5), (1, 1, 5), (5, 5, 5)</font> |                                  |                                 |
| T6   | commit;                                                      |                                  |                                 |

可以看到，session A 里执行了三次查询，分别是 Q1、Q2 和 Q3。它们的 SQL 语句相同，都是 select * from t where d=5 for update。这个语句的意思就是查所有 d=5 的行，而且使用的是当前读，并且加上写锁。现在，那么这三条 SQL 语句，分别会返回什么结果。

1.  Q1 只返回 id=5 这一行；
2.  在 T2 时刻，session B 把 id=0 这一行的 d 值改成了 5，因此 T3 时刻 Q2 查出来的是 id=0 和 id=5 这两行；
3.  在 T4 时刻，session C 又插入一行（1,1,5），因此 T5 时刻 Q3 查出来的是 id=0、id=1 和 id=5 的这三行。

其中，Q3 读到 id=1 这一行的现象，被称为“幻读”。也就是说，**幻读指的是一个事务在前后两次查询同一个范围的时候，后一次查询看到了前一次查询没有看到的行**。

这里，需要对“幻读”做一个说明：

1.  在可重复读隔离级别下，普通的查询是快照读，是不会看到别的事务插入的数据的。因此，幻读在“当前读”下才会出现。
2.  上面 session B 的修改结果，被 session A 之后的 select 语句用“当前读”看到，不能称为幻读。幻读仅专指“新插入的行”。

如果只从**事务可见性规则**来分析的话，上面这三条 SQL 语句的返回结果都没有问题。

因为这三个查询都是加了 for update，都是当前读。而当前读的规则，就是要能读到所有已经提交的记录的最新值。并且，session B 和 sessionC 的两条语句，执行后就会提交，所以 Q2 和 Q3 就是应该看到这两个事务的操作效果，而且也看到了，这跟事务的可见性规则并不矛盾。

但是，这是不是真的没问题呢？

不，这里还真就有问题。

## 幻读有什么问题？

**首先是语义上的。**session A 在 T1 时刻就声明了，“我要把所有 d=5 的行锁住，不准别的事务进行读写操作”。而实际上，这个语义被破坏了。

如果现在这样看感觉还不明显的话，再往 session B 和 session C 里面分别加一条 SQL 语句，再看看会出现什么现象。

|      | session A                                                   | session B                                                    | Session C                                                    |
| ---- | ----------------------------------------------------------- | ------------------------------------------------------------ | ------------------------------------------------------------ |
| T1   | begin;<br />select * from t where d = 5 for update;/\*Q1\*/ |                                                              |                                                              |
| T2   |                                                             | update t set d = 5 where id = 0;<br />update t set c = 5 where id = 0; |                                                              |
| T3   | select * from t where d = 5 for update;/\*Q2\*/             |                                                              |                                                              |
| T4   |                                                             |                                                              | insert into t values (1, 1, 5);<br />update t set c = 5 where id =1; |
| T5   | select * from t where d = 5 for update;/\*Q3\*/             |                                                              |                                                              |
| T6   | commit;                                                     |                                                              |                                                              |

session B 的第二条语句 update t set c=5 where id=0，语义是“我把 id=0、d=5 这一行的 c 值，改成了 5”。

由于在 T1 时刻，session A 还只是给 id=5 这一行加了行锁， 并没有给 id=0 这行加上锁。因此，session B 在 T2 时刻，是可以执行这两条 update 语句的。这样，就破坏了 session A 里 Q1 语句要锁住所有 d=5 的行的加锁声明。

session C 也是一样的道理，对 id=1 这一行的修改，也是破坏了 Q1 的加锁声明。

**其次，是数据一致性的问题。**

首先，锁的设计是为了保证数据的一致性。而这个一致性，不止是数据库内部数据状态在此刻的一致性，还包含了数据和日志在逻辑上的一致性。

为了说明这个问题，给 session A 在 T1 时刻再加一个更新语句，即：update t set d=100 where d=5。

|      | session A                                                    | session B                                                    | Session C                                                    |
| ---- | ------------------------------------------------------------ | ------------------------------------------------------------ | ------------------------------------------------------------ |
| T1   | begin;<br />select * from t where d = 5 for update;/\*Q1\*/<br />update t set d = 100 where d = 5; |                                                              |                                                              |
| T2   |                                                              | update t set d = 5 where id = 0;<br />update t set c = 5 where id = 0; |                                                              |
| T3   | select * from t where d = 5 for update;/\*Q2\*/              |                                                              |                                                              |
| T4   |                                                              |                                                              | insert into t values (1, 1, 5);<br />update t set c = 5 where id =1; |
| T5   | select * from t where d = 5 for update;/\*Q3\*/              |                                                              |                                                              |
| T6   | commit;                                                      |                                                              |                                                              |

update 的加锁语义和 select …for update 是一致的，所以这时候加上这条 update 语句也很合理。session A 声明说“要给 d=5 的语句加上锁”，就是为了要更新数据，新加的这条 update 语句就是把它认为加上了锁的这一行的 d 值修改成了 100。

现在，再来分析一下上表执行完成后，数据库里会是什么结果。

1.  经过 T1 时刻，id=5 这一行变成 (5,5,100)，当然这个结果最终是在 T6 时刻正式提交的 ;
2.  经过 T2 时刻，id=0 这一行变成 (0,5,5);
3.  经过 T4 时刻，表里面多了一行 (1,5,5);
4.  其他行跟这个执行序列无关，保持不变。

这样看，这些数据也没啥问题，但是再来看看这时候 binlog 里面的内容。

1.  T2 时刻，session B 事务提交，写入了两条语句；
2.  T4 时刻，session C 事务提交，写入了两条语句；
3.  T6 时刻，session A 事务提交，写入了 update t set d=100 where d=5 这条语句。

统一放到一起的话，就是这样的：

```sql
update t set d=5 where id=0; /*(0,0,5)*/
update t set c=5 where id=0; /*(0,5,5)*/
 
insert into t values(1,1,5); /*(1,1,5)*/
update t set c=5 where id=1; /*(1,5,5)*/
 
update t set d=100 where d=5;/* 所有 d=5 的行，d 改成 100*/
```

好，那么问题就来了。这个语句序列，不论是拿到备库去执行，还是以后用 binlog 来克隆一个库，这三行的结果，都变成了 (0,5,100)、(1,5,100) 和 (5,5,100)。

也就是说，id=0 和 id=1 这两行，发生了数据不一致。这个问题很严重，是不行的。

到这里，再回顾一下，**这个数据不一致到底是怎么引入的？**

分析一下可以知道，这是假设“select * from t where d=5 for update 这条语句只给 d=5 这一行，也就是 id=5 的这一行加锁”导致的。

把扫描过程中碰到的行，也都加上写锁，再来看看执行效果。



|      | session A                                                    | session B                                                    | Session C                                                    |
| ---- | ------------------------------------------------------------ | ------------------------------------------------------------ | ------------------------------------------------------------ |
| T1   | begin;<br />select * from t where d = 5 for update;/\*Q1\*/<br />update t set d = 100 where d = 5; |                                                              |                                                              |
| T2   |                                                              | update t set d = 5 where id = 0;<br /><font color='red'> (blocked) </font><br />update t set c = 5 where id = 0; |                                                              |
| T3   | select * from t where d = 5 for update;/\*Q2\*/              |                                                              |                                                              |
| T4   |                                                              |                                                              | insert into t values (1, 1, 5);<br />update t set c = 5 where id =1; |
| T5   | select * from t where d = 5 for update;/\*Q3\*/              |                                                              |                                                              |
| T6   | commit;                                                      |                                                              |                                                              |

由于 session A 把所有的行都加了写锁，所以 session B 在执行第一个 update 语句的时候就被锁住了。需要等到 T6 时刻 session A 提交以后，session B 才能继续执行。

这样对于 id=0 这一行，在数据库里的最终结果还是 (0,5,5)。在 binlog 里面，执行序列是这样的：

```sql
insert into t values(1,1,5); /*(1,1,5)*/
update t set c=5 where id=1; /*(1,5,5)*/
 
update t set d=100 where d=5;/* 所有 d=5 的行，d 改成 100*/
 
update t set d=5 where id=0; /*(0,0,5)*/
update t set c=5 where id=0; /*(0,5,5)*/
```

可以看到，按照日志顺序执行，id=0 这一行的最终结果也是 (0,5,5)。所以，id=0 这一行的问题解决了。

但同时也可以看到，id=1 这一行，在数据库里面的结果是 (1,5,5)，而根据 binlog 的执行结果是 (1,5,100)，也就是说幻读的问题还是没有解决。

为什么我们已经把所有的记录都上了锁，还是阻止不了 id=1 这一行的插入和更新呢？

原因很简单。在 T3 时刻，给所有行加锁的时候，id=1 这一行还不存在，不存在也就加不上锁。

**也就是说，即使把所有的记录都加上锁，还是阻止不了新插入的记录，**这也是为什么“幻读”会被单独拿出来解决的原因。

到这里，其实我们刚说明完文章的标题 ：幻读的定义和幻读有什么问题。

接下来，再看看 InnoDB 怎么解决幻读的问题。

## 如何解决幻读？

现在知道了，产生幻读的原因是，行锁只能锁住行，但是新插入记录这个动作，要更新的是记录之间的“间隙”。因此，为了解决幻读问题，InnoDB 只好引入新的锁，也就是间隙锁 (Gap Lock)。

顾名思义，间隙锁，锁的就是两个值之间的空隙。比如文章开头的表 t，初始化插入了 6 个记录，这就产生了 7 个间隙。

![MySQL-20-1](img/MySQL-20-1.png)

这样，当执行 select * from t where d=5 for update 的时候，就不止是给数据库中已有的 6 个记录加上了行锁，还同时加了 7 个间隙锁。这样就确保了无法再插入新的记录。

也就是说这时候，在一行行扫描的过程中，不仅将给行加上了行锁，还给行两边的空隙，也加上了间隙锁。

现在知道了，数据行是可以加上锁的实体，数据行之间的间隙，也是可以加上锁的实体。但是间隙锁跟之前碰到过的锁都不太一样。

比如行锁，分成读锁和写锁。下图就是这两种类型行锁的冲突关系。

![MySQL-20-2](img/MySQL-20-2.png)

也就是说，跟行锁有冲突关系的是“另外一个行锁”。

但是间隙锁不一样，**跟间隙锁存在冲突关系的，是“往这个间隙中插入一个记录”这个操作。**间隙锁之间都不存在冲突关系。

这句话不太好理解，举个例子：

| session A                                                   | session B                                           |
| ----------------------------------------------------------- | --------------------------------------------------- |
| begin;<br />select * from t where c = 7 lock in share mode; |                                                     |
|                                                             | begin;<br />select * from t where c = 7 for update; |

这里 session B 并不会被堵住。因为表 t 里并没有 c=7 这个记录，因此 session A 加的是间隙锁 (5,10)。而 session B 也是在这个间隙加的间隙锁。它们有共同的目标，即：保护这个间隙，不允许插入值。但，它们之间是不冲突的。

间隙锁和行锁合称 next-key lock，每个 next-key lock 是**前开后闭区间**。也就是说，表 t 初始化以后，如果用 select * from t for update 要把整个表所有记录锁起来，就形成了 7 个 next-key lock，分别是 (-∞,0]、(0,5]、(5,10]、(10,15]、(15,20]、(20, 25]、(25, +supremum]。

>   备注：这篇文章中，如果没有特别说明，把间隙锁记为开区间，把 next-key lock 记为前开后闭区间。

那么，这个 supremum 从哪儿来的呢？

这是因为 +∞是开区间。实现上，InnoDB 给每个索引加了一个不存在的最大值 supremum，这样才符合我们前面说的“都是前开后闭区间”。

**间隙锁和 next-key lock 的引入，帮我们解决了幻读的问题，但同时也带来了一些“困扰”。**

---

任意锁住一行，如果这一行不存在的话就插入，如果存在这一行就更新它的数据，代码如下：

```sql
begin;
select * from t where id=N for update;
 
/* 如果行不存在 */
insert into t values(N,N,N);
/* 如果行存在 */
update t set d=N set id=N;
 
commit;
```

那么问题是，这个逻辑一旦有并发，就会碰到死锁。你一定也觉得奇怪，这个逻辑每次操作前用 for update 锁起来，已经是最严格的模式了，怎么还会有死锁呢？

这里，用两个 session 来模拟并发，并假设 N=9。



| session A                                                    | session B                                                    |
| ------------------------------------------------------------ | ------------------------------------------------------------ |
| begin;<br />select * from t where id = 9 for update;         |                                                              |
|                                                              | begin;<br />select * from t where id = 9 for update;         |
|                                                              | insert into t values(9, 9, 9);<br /><font color='red'> (blocked)</font> |
| insert into t values(9, 9, 9);<br /><font color='red'>(ERROR 1213 (40001): Deadlock found)</font> |                                                              |

看到了，其实都不需要用到后面的 update 语句，就已经形成死锁了。按语句执行顺序来分析一下：

1.  session A 执行 select … for update 语句，由于 id=9 这一行并不存在，因此会加上间隙锁 (5,10);
2.  session B 执行 select … for update 语句，同样会加上间隙锁 (5,10)，间隙锁之间不会冲突，因此这个语句可以执行成功；
3.  session B 试图插入一行 (9,9,9)，被 session A 的间隙锁挡住了，只好进入等待；
4.  session A 试图插入一行 (9,9,9)，被 session B 的间隙锁挡住了。

至此，两个 session 进入互相等待状态，形成死锁。当然，InnoDB 的死锁检测马上就发现了这对死锁关系，让 session A 的 insert 语句报错返回了。

现在知道了，**间隙锁的引入，可能会导致同样的语句锁住更大的范围，这其实是影响了并发度的**。

---

为了解决幻读的问题，引入了这么一大串内容，有没有更简单一点的处理方法呢。

**在可重复读隔离级别下的，间隙锁是在可重复读隔离级别下才会生效的**。所以，如果把隔离级别设置为读提交的话，就没有间隙锁了。但同时，要解决可能出现的数据和日志不一致问题，需要把 binlog 格式设置为 row。这，也是现在不少公司使用的配置组合。



## 小结

今天从上一篇文章的课后问题说起，提到了全表扫描的加锁方式。发现即使给所有的行都加上行锁，仍然无法解决幻读问题，因此引入了**间隙锁**的概念。



行锁确实比较直观，判断规则也相对简单，间隙锁的引入会影响系统的并发度，也增加了锁分析的复杂度，但也有章可循。

### 思考题

| session A                                                    | session B                         | session C                      |
| ------------------------------------------------------------ | --------------------------------- | ------------------------------ |
| begin;<br />select * from t where c >= 15 and c <= 20 order by c desc for update; |                                   |                                |
|                                                              | insert into t values(11, 11, 11); |                                |
|                                                              |                                   | insert into t values(6, 6, 6); |

这里 session B 和 session C 的 insert 语句都会进入锁等待状态。

