# JVM 字节码文件

你可能仍对以下这些问题有疑问：

-   怎么查看字节码文件？
-   字节码文件长什么样子？
-   对象初始化之后，具体的字节码又是怎么执行的？

带着这些疑问，我们进入本课时的学习，本课时将带你动手实践，详细分析一个 Java 文件产生的字节码，并从栈帧层面看一下字节码的具体执行过程。

## **工具介绍**

工欲善其事，必先利其器。在开始本课时的内容之前，先给你介绍两个分析字节码的小工具。

### **javap**

第一个小工具是 javap，javap 是 JDK 自带的反解析工具。它的作用是将 .class 字节码文件解析成可读的文件格式。我们在第一课时，就是用的它输出了 HelloWorld 的内容。



在使用 javap 时我一般会添加 -v 参数，尽量多打印一些信息。同时，我也会使用 -p 参数，打印一些私有的字段和方法。使用起来大概是这样：

```
javap -p -v HelloWorld
```

在 Stack Overflow 上有一个非常有意思的问题：我在某个类中增加一行注释之后，为什么两次生成的 .class 文件，它们的 MD5 是不一样的？



这是因为在 javac 中可以指定一些额外的内容输出到字节码。经常用的有

-   **javac -g:lines** 强制生成 LineNumberTable。
-   **javac -g:vars** 强制生成 LocalVariableTable。
-   **javac -g** 生成所有的 debug 信息。

为了观察字节码的流转，我们本课时就会使用到这些参数。

### **jclasslib**

如果你不太习惯使用命令行的操作，还可以使用 jclasslib，jclasslib 是一个图形化的工具，能够更加直观的查看字节码中的内容。它还分门别类的对类中的各个部分进行了整理，非常的人性化。同时，它还提供了 Idea 的插件，你可以从 plugins 中搜索到它。



如果你在其中看不到一些诸如 LocalVariableTable 的信息，记得在编译代码的时候加上我们上面提到的这些参数。



jclasslib 的下载地址：https://github.com/ingokegel/jclasslib

## **类加载和对象创建的时机**

接下来，我们来看一个稍微复杂的例子，来具体看一下类加载和对象创建的过程。



首先，我们写一个最简单的 Java 程序 A.java。它有一个公共方法 test，还有一个静态成员变量和动态成员变量。

```
class B {
    private int a = 1234;

    static long C = 1111;

    public long test(long num) {
        long ret = this.a + num + C;
        return ret;
    }
}

public class A {
    private B b = new B();

    public static void main(String[] args) {
        A a = new A();
        long num = 4321 ;

        long ret = a.b.test(num);

        System.out.println(ret);
    }
}
```

前面我们提到，类的初始化发生在类加载阶段，那对象都有哪些创建方式呢？除了我们常用的 new，还有下面这些方式：

-   使用 Class 的 newInstance 方法。
-   使用 Constructor 类的 newInstance 方法。
-   反序列化。
-   使用 Object 的 clone 方法。

其中，后面两种方式没有调用到构造函数。



当虚拟机遇到一条 new 指令时，首先会检查这个指令的参数能否在常量池中定位一个符号引用。然后检查这个符号引用的类字节码是否加载、解析和初始化。如果没有，将执行对应的类加载过程。



拿我们上面的代码来说，执行 A 代码，在调用 private B b = new B() 时，就会触发 B 类的加载。 

![img](img/CgpOIF4ezuOAK_6bAACFY5oeX-Y174.jpg)

让我们结合上图回顾一下前面章节的内容。A 和 B 会被加载到元空间的方法区，进入 main 方法后，将会交给执行引擎执行。这个执行过程是在栈上完成的，其中有几个重要的区域，包括虚拟机栈、程序计数器等。接下来我们详细看一下虚拟机栈上的执行过程。