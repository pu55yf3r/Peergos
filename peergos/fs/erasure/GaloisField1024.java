package peergos.fs.erasure;

public class GaloisField1024
{
    private static final int POWER = 10;
    private static final int SIZE = 1 << POWER;
    private static final int[] exp = new int[2*SIZE];
    private static final int[] log = new int[SIZE];
    static {
        exp[0] = 1;
        int x = 1;
        for (int i=1; i < SIZE-1; i++)
        {
            x <<= 1;
            if ((x & SIZE) != 0)
                x ^= (SIZE | 0x3); // x^n = 1 + x
            exp[i] = x;
            log[x] = i;
        }
        for (int i=SIZE-1; i < 2*SIZE; i++)
            exp[i] = exp[i+1-SIZE];
        log[exp[SIZE-1]] = SIZE-1;
        // check
        for (int i=0; i < SIZE; i++) {
            assert (log[exp[i]] == i);
            assert (exp[log[i]] == i);
        }
    }

    public static int size()
    {
        return SIZE;
    }

    public static int mask()
    {
        return SIZE-1;
    }

    public static int exp(int y)
    {
        return exp[y];
    }

    public static int mul(int x, int y)
    {
        if ((x==0) || (y==0))
            return 0;
        return exp[log[x]+log[y]];
    }

    public static int div(int x, int y)
    {
        if (y==0)
            throw new IllegalStateException("Divided by zero! Blackhole created.. ");
        if (x==0)
            return 0;
        return exp[log[x]+SIZE-1-log[y]];
    }
}