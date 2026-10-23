import com.android.apksig.ApkSigner;

import java.io.File;
import java.io.FileInputStream;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.Collections;

/** Assina o APK com v1+v2+v3 usando a biblioteca apksig (a mesma do apksigner do Android). */
public class Signer {
    public static void main(String[] a) throws Exception {
        String in = a[0], out = a[1], ks = a[2], pass = a[3], alias = a[4];
        KeyStore k = KeyStore.getInstance("PKCS12");
        try (FileInputStream f = new FileInputStream(ks)) { k.load(f, pass.toCharArray()); }
        PrivateKey key = (PrivateKey) k.getKey(alias, pass.toCharArray());
        X509Certificate cert = (X509Certificate) k.getCertificate(alias);
        ApkSigner.SignerConfig sc = new ApkSigner.SignerConfig.Builder("tripper", key, Collections.singletonList(cert)).build();
        new ApkSigner.Builder(Collections.singletonList(sc))
                .setInputApk(new File(in))
                .setOutputApk(new File(out))
                .setMinSdkVersion(26)
                .setV1SigningEnabled(true)
                .setV2SigningEnabled(true)
                .setV3SigningEnabled(true)
                .build()
                .sign();
        System.out.println("assinado: " + out);
    }
}
