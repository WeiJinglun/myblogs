# Integer与int 深入理解

今天在做Object 自动转为Integer 类型之后的判断，遇到一个不理解的点，当数值超过127之后，两个数值相同的Object 对象用 == 判断的结果是false。

```java
        Object a = 128;
        Object b = 128;
        
        System.out.println(a.getClass().getName());
        System.out.println(b.getClass().getName());
        System.out.println(a==b);
        
        
        Object a1 = 127;
        Object b1 = 127;
        
        System.out.println(a1.getClass().getName());
        System.out.println(b1.getClass().getName());
        System.out.println(a1==b1);
       
        int a2 = 128;
        int b2 = 128;
        
        System.out.println(a2==b2);
```

>   结果：
>
>   false
>   true
>   true

之前隐约记得数值在 -128 与 127之间时，Integer 对象会特别处理，但是具体怎么处理的忘记了，网上查了些资料终于明白背后的设计原理了。



1.  Java 中的数据类型分为基本数据类型和引用数据类型

　　int是基本数据类型，Integer是引用数据类型；

　　Ingeter是int的包装类，int的初值为0，Ingeter的初值为null；

2.  自动装箱和拆箱 

　　从Java5.0版本以后加入了autoboxing功能，自动**拆箱**和**装箱**是依靠JDK的编译器在编译期的预处理工作。

-   自动装箱：将基本数据类型封装为对象类型，成为一个对象以后就可以调用对象所声明的所有的方法。

```java
　　 Integer inA = 127;
    //以上的声明就是用到了自动的装箱：解析为
    Integer inA = new Integer(127);
```

-   自动拆箱：将对象重新转化为基本数据类型。

```java
　　//装箱
   Integer inB = 128;
   //拆箱
   int inC = inB;
```

-   自动拆箱很典型的用法就是在进行运算的时候：因为对象不能直接进行运算，需要转化为基本数据类型后才能进行加减乘除。

```java
Integer inD = 128;
System.out.println(inD--);
```

　　

3.  回到我遇到的问题：为什么数值在 **-128 与 127之间**时，两个Integer 对象是否相等可以用 ==来判断，但是这个范围之外的就不能了呢？

>   这是因为Java对于Integer 与int 的自动装箱与拆箱的设计，是一种模式：**享元模式(flyweight)，**为了加大对简单数字的重利用，Java定义：在自动装箱时对于值从–128到127之间的值，它们被装箱为Integer对象后，会存在内存中被重用，始终只存在一个对象。而如果超过了这之间的值，被装箱后的Integer 对象并不会被重用，即相当于每次装箱时都新建一个 Integer对象；

以上的现象是由于使用了自动装箱所引起的，如果你没有使用自动装箱，而是跟一般类一样，用new来进行实例化，就会每次new就都一个新的对象；这个的自动装箱拆箱不仅在基本数据类型中有应用，在String类中也有应用。

