package moe.ouom.wekit.security;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;

import androidx.annotation.NonNull;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import moe.ouom.wekit.BuildConfig;
import moe.ouom.wekit.util.log.WeLogger;

/**
 * 签名校验器 - 防止模块被篡改
 */
public class SignatureVerifier {

    private static final String TAG = "SignatureVerifier";

    private static final String[] VALID_SIGNATURE_HASHES = {
        "156B65C9CBE827BF0BB22F9E00BEEC3258319CE8A15D2A3729275CAF71CEDA21"
    };

    private static volatile boolean sSignatureValid = false;
    private static volatile boolean sVerified = false;

    /**
     * 验证应用签名
     */
    public static boolean verifySignature(@NonNull Context context) {
        if (BuildConfig.DEBUG) {
            return true;
        }

        if (sVerified) {
            return sSignatureValid;
        }

        synchronized (SignatureVerifier.class) {
            if (sVerified) {
                return sSignatureValid;
            }

            try {
                String modulePackageName = BuildConfig.APPLICATION_ID;

                PackageManager pm = context.getPackageManager();
                PackageInfo packageInfo;

                packageInfo = pm.getPackageInfo(modulePackageName, PackageManager.GET_SIGNING_CERTIFICATES);

                if (packageInfo.signingInfo != null) {
                    Signature[] signatures = packageInfo.signingInfo.getApkContentsSigners();
                    sSignatureValid = verifySignatures(signatures);
                } else if (packageInfo.signatures != null) {
                    sSignatureValid = verifySignatures(packageInfo.signatures);
                }

                sVerified = true;

                if (!sSignatureValid) {
                    WeLogger.e(TAG, "签名校验失败！模块已被篡改，所有功能将被禁用");
                } else {
                    WeLogger.i(TAG, "签名校验通过");
                }

                return sSignatureValid;

            } catch (Exception e) {
                WeLogger.e("签名校验异常", e);
                sVerified = true;
                sSignatureValid = false;
                return false;
            }
        }
    }

    /**
     * 验证签名数组
     */
    private static boolean verifySignatures(Signature[] signatures) {
        if (signatures == null) {
            return false;
        }

        for (Signature signature : signatures) {
            String signatureHash = getSignatureHash(signature);
            if (signatureHash != null) {
                // Java 层验证
                for (String validHash : VALID_SIGNATURE_HASHES) {
                    if (validHash.equalsIgnoreCase(signatureHash)) {
                        return true;
                    }
                }
            }
        }

        return false;
    }

    /**
     * 获取签名的SHA256哈希值
     */
    private static String getSignatureHash(Signature signature) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(signature.toByteArray());
            byte[] digest = md.digest();

            StringBuilder hexString = new StringBuilder();
            for (byte b : digest) {
                String hex = Integer.toHexString(0xFF & b);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }
            return hexString.toString().toUpperCase();

        } catch (NoSuchAlgorithmException e) {
            WeLogger.e("SHA-256 算法不可用", e);
            return null;
        }
    }

    /**
     * 检查签名是否有效
     */
    public static boolean isSignatureValid() {
        if (BuildConfig.DEBUG) {
            return true;
        }
        return sVerified && sSignatureValid;
    }

    /**
     * 重置验证状态（仅用于测试）
     */
    public static void resetVerification() {
        sVerified = false;
        sSignatureValid = false;
    }
}
