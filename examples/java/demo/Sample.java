package demo;

public class Sample {
    private int calls;
    public int add(int left, int right) {
        return left + right;
    }
    public int run() {
        return add(1, 2);
    }
}
