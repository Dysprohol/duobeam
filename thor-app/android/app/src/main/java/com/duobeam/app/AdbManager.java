package com.duobeam.app;

import android.content.Context;
import android.os.Build;
import android.sun.security.x509.AlgorithmId;
import android.sun.security.x509.CertificateAlgorithmId;
import android.sun.security.x509.CertificateExtensions;
import android.sun.security.x509.CertificateIssuerName;
import android.sun.security.x509.CertificateSerialNumber;
import android.sun.security.x509.CertificateSubjectName;
import android.sun.security.x509.CertificateValidity;
import android.sun.security.x509.CertificateVersion;
import android.sun.security.x509.CertificateX509Key;
import android.sun.security.x509.KeyIdentifier;
import android.sun.security.x509.PrivateKeyUsageExtension;
import android.sun.security.x509.SubjectKeyIdentifierExtension;
import android.sun.security.x509.X500Name;
import android.sun.security.x509.X509CertImpl;
import android.sun.security.x509.X509CertInfo;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Date;

import io.github.muntashirakon.adb.AbsAdbConnectionManager;

/**
 * ADB client based on the libadb-android sample. Holds the app's own RSA key and self-signed X.509 certificate,
 * which adbd remembers after wireless debugging pairing.
 */
final class AdbManager extends AbsAdbConnectionManager {

    private static AdbManager instance;
    private final PrivateKey privateKey;
    private final Certificate certificate;

    static synchronized AdbManager get(Context c) throws Exception {
        if (instance == null) instance = new AdbManager(c.getApplicationContext());
        return instance;
    }

    private AdbManager(Context c) throws Exception {
        setApi(Build.VERSION.SDK_INT);
        File keyFile = new File(c.getFilesDir(), "adb.key"), certFile = new File(c.getFilesDir(), "adb.crt");
        if (keyFile.exists() && certFile.exists()) {
            privateKey = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(Files.readAllBytes(keyFile.toPath())));
            try (FileInputStream in = new FileInputStream(certFile)) {
                certificate = CertificateFactory.getInstance("X.509").generateCertificate(in);
            }
            return;
        }
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048, new SecureRandom());
        KeyPair kp = gen.generateKeyPair();
        PublicKey pub = kp.getPublic();
        privateKey = kp.getPrivate();
        String alg = "SHA512withRSA";
        Date notBefore = new Date(), notAfter = new Date(System.currentTimeMillis() + 30L * 365 * 86400000L);
        CertificateExtensions ext = new CertificateExtensions();
        ext.set("SubjectKeyIdentifier", new SubjectKeyIdentifierExtension(new KeyIdentifier(pub).getIdentifier()));
        ext.set("PrivateKeyUsage", new PrivateKeyUsageExtension(notBefore, notAfter));
        X500Name name = new X500Name("CN=DuoBeam");
        X509CertInfo info = new X509CertInfo();
        info.set("version", new CertificateVersion(2));
        info.set("serialNumber", new CertificateSerialNumber(new SecureRandom().nextInt() & Integer.MAX_VALUE));
        info.set("algorithmID", new CertificateAlgorithmId(AlgorithmId.get(alg)));
        info.set("subject", new CertificateSubjectName(name));
        info.set("key", new CertificateX509Key(pub));
        info.set("validity", new CertificateValidity(notBefore, notAfter));
        info.set("issuer", new CertificateIssuerName(name));
        info.set("extensions", ext);
        X509CertImpl cert = new X509CertImpl(info);
        cert.sign(privateKey, alg);
        certificate = cert;
        try (FileOutputStream out = new FileOutputStream(keyFile)) {
            out.write(privateKey.getEncoded());
        }
        try (FileOutputStream out = new FileOutputStream(certFile)) {
            out.write(certificate.getEncoded()); // DER; CertificateFactory reads DER as well
        }
    }

    @Override
    protected PrivateKey getPrivateKey() {
        return privateKey;
    }

    @Override
    protected Certificate getCertificate() {
        return certificate;
    }

    @Override
    protected String getDeviceName() {
        return "DuoBeam";
    }
}
