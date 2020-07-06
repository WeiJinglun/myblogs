/**
 * @author WeiJinglun
 * @date 2020.04.13
 **/
public class A {
    private B b = new B();

    public static void main(String[] args) {
        A a = new A();
        long num = 4321;

        long ret = a.b.test(num);

        System.out.println(ret);
    }
}

class B {
    private int a = 1234;

    static long C = 1111;

    public long test(long num) {
        return this.a + num + C;
    }
}
