import com.android.apksig.ApkVerifier;

import java.io.File;

public class Verify {
    public static void main(String[] a) throws Exception {
        ApkVerifier.Result r = new ApkVerifier.Builder(new File(a[0])).build().verify();
        System.out.println("verificado=" + r.isVerified() + " v1=" + r.isVerifiedUsingV1Scheme()
                + " v2=" + r.isVerifiedUsingV2Scheme() + " v3=" + r.isVerifiedUsingV3Scheme());
        for (Object e : r.getErrors()) System.out.println("ERRO " + e);
        for (Object w : r.getWarnings()) System.out.println("aviso " + w);
    }
}
