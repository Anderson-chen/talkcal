public class Meal {
    public Meal(int calories) {
        System.out.println(calories);
    }
}

void main () throws InterruptedException {
    new Meal(1);
    Thread.sleep(2000);
    new Meal(2 );
}
