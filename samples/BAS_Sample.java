import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.Future;

@SuppressWarnings("all")
public class BAS_Sample {
    private final Map<String, String> m = new HashMap<String, String>();

    public void testIfScope(String s) {
        Object o = new Object();
        if (s.equals("Foo")) {
            s = o.toString();
        }
    }

    public String testFPForScope(String s) {
        Object o = new Object();
        while (s.length() > 0) {
            o = s.substring(0, 1);
            s = s.substring(1);
        }
        return s;
    }

    public String testFP2Scopes(String s) {
        Object o = new Object();
        if (s.equals("Boo")) {
            s = o.toString();
        } else if (s.equals("Hoo")) {
            s = o.toString();
        }

        return s;
    }

    public String test2InnerScopes(String s) {
        Object o = new Object();
        if (s != null) {
            if (s.equals("Boo")) {
                s = o.toString();
            } else if (s.equals("Hoo")) {
                s = o.toString();
            }
        }

        return s;
    }

    public String testFPLoopCond(List<String> in) {
        StringBuilder sb = new StringBuilder();
        for (String s : in) {
            sb.append(s);
        }
        return sb.toString();
    }

    public List<String> getList() {
        return null;
    }

    public String testSwitch(int a) {
        String v = "Test";

        switch (a) {
        case 1:
            v = "Testa";
            break;

        case 2:
            v = "Tesseract";
            break;

        case 3:
            v = "Testy";
            break;

        default:
            v = "Rossa";
            break;
        }

        return null;
    }

    public void testFPSync(Set<String> a, Set<String> b) {
        String c, d;

        synchronized (this) {
            c = a.iterator().next();
            d = b.iterator().next();
        }

        if (d.length() > 0) {
            d = c;
        }
    }

    public int testFPObjChange(Calendar c, boolean b) {
        int hour = c.get(Calendar.HOUR_OF_DAY);
        c.set(2000, Calendar.JANUARY, 1);

        if (b) {
            return hour;
        }

        return 0;
    }

    public void tstFPRefChange(Holder h1, Holder h2, boolean b) {

        int h = h1.member;
        h1 = h2;

        if (b) {
            System.out.println(h);
        }
    }
    
    public void tstFPRefChangeThruMethodChain(Holder h1, Holder h2, boolean b) {

        String h = h1.toString().trim();
        h1 = h2;

        if (b) {
            System.out.println(h);
        }
    }

    public void testFPSrcOverwrite(int src, boolean b) {
        int d = src;
        src = 0;

        if (b) {
            System.out.println(d);
        }
    }

    public void testFPRiskies1(boolean b) {
        long start = System.currentTimeMillis();

        if (b) {
            long delta = System.currentTimeMillis() - start;
            System.out.println(delta);
        }
    }

    public String testFPIteratorNext(Collection<String> c, boolean b) {
        Iterator<String> it = c.iterator();

        String s = it.next();

        if (b) {
            if (s == null) {
                return "yay";
            }
        }

        return it.next();
    }

    public List<String> testFPSynchronized(String s, String p) {
        List<String> l = new ArrayList<String>();
        String x = s;
        synchronized (s) {
            if (p != null) {
                l.add(p);
                return l;
            }
        }

        return null;
    }

    public void testFPNestedIfs(Map<String, String> x, int i, boolean b) {

        String s = x.get("hello");

        if (i == 0) {
            if (b) {
                System.out.println(s);
            }
        } else if (i == 1) {
            System.out.println(s);
        } else if (i == 2) {
            System.out.println(s);
        }
    }

    public boolean testFPFuture(boolean b) {
        ExecutorService s = new ForkJoinPool();
        Future f = s.submit(new Runnable() {
            public void run() {
            }
        });
        if (b) {
            if (f.isDone()) {
                return true;
            }
        }

        return false;
    }

    public int testFPTwoCatches(List<Integer> x) throws Exception {
        String msg = "This is a test";
        try {
            return x.size() * x.get(0);
        } catch (NullPointerException e) {
            throw new Exception(msg + "NPE", e);
        } catch (IndexOutOfBoundsException e) {
            throw new Exception(msg + "IIOBE", e);
        }
    }

    static class Holder {
        int member;
    }
}
