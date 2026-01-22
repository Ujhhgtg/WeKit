package moe.ouom.wekit.util.io;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Objects;

import moe.ouom.wekit.util.log.WeLogger;

@SuppressWarnings("ResultOfMethodCallIgnored")
public class FileUtils {

    private static final int BYTE_SIZE = 1024;

    private static void renameSuffix(String path, String suffix) {
        File file = new File(path);
        String oldName = path.substring(0, path.lastIndexOf("."));
        file.renameTo(new File(file.getAbsolutePath(), oldName + suffix));
    }

    public static void writeBytesToFile(String path, byte[] data) {
        File file = new File(path);
        try {
            if (!Objects.requireNonNull(file.getParentFile()).exists()) file.getParentFile().mkdirs();
            if (!file.exists()) file.createNewFile();
        } catch (IOException e) {
            WeLogger.e("FileUtils", e);
        }
        try (BufferedOutputStream bufOut = new BufferedOutputStream(new FileOutputStream(path))) {
            bufOut.write(data);
        } catch (IOException ioException) {
            throw new RuntimeException(ioException);
        }
    }

    /**
     * 文件转byte
     */
    public static byte[] readAllByteArrayFromFile(File file) {
        try {
            return readAllByte(new FileInputStream(file), (int) file.length());
        } catch (FileNotFoundException e) {
            throw new RuntimeException(e);
        }
    }

    public static String getFileMD5(File file) {
        if (!file.isFile()) {
            return null;
        }
        MessageDigest digest = null;
        FileInputStream in = null;
        byte[] buffer = new byte[1024];
        int len;
        try {
            digest = MessageDigest.getInstance("MD5");
            in = new FileInputStream(file);
            while ((len = in.read(buffer, 0, 1024)) != -1) {
                digest.update(buffer, 0, len);
            }
            in.close();
        } catch (Exception e) {
            WeLogger.e("FileUtils", e);
            return null;
        }
        BigInteger bigInt = new BigInteger(1, digest.digest());
        return bigInt.toString(16).toUpperCase();
    }

    public static byte[] readAllBytes(InputStream inp) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int read;
        while ((read = inp.read(buffer)) != -1) out.write(buffer, 0, read);
        return out.toByteArray();
    }

    public static byte[] readAllByte(InputStream stream, int size) {
        try {
            byte[] buffer = new byte[BYTE_SIZE];
            ByteArrayOutputStream bytearrayOut = new ByteArrayOutputStream();
            int read;
            while ((read = stream.read(buffer)) != -1) {
                bytearrayOut.write(buffer, 0, read);
            }
            return bytearrayOut.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * 复制文件夹
     *
     * @param sourceDir 原文件夹
     * @param targetDir 复制后的文件夹
     */
    public static void copyDir(File sourceDir, File targetDir) {
        if (!sourceDir.isDirectory()) {
            return;
        }
        if (!targetDir.exists()) {
            targetDir.mkdirs();
        }
        File[] files = sourceDir.listFiles();
        assert files != null;
        for (File f : files) {
            if (f.isDirectory()) {
                copyDir(f, new File(targetDir.getPath(), f.getName()));
            } else if (f.isFile()) {
                try {
                    copyFile(f, new File(targetDir.getPath(), f.getName()));
                } catch (IOException e) {
                    WeLogger.e("FileUtils", e);
                }
            }
        }
    }

    public static void deleteFile(File file) {
        try {
            if (file == null) return;
            if (file.isFile()) file.delete();
            File[] files = file.listFiles();
            if (files == null) return;

            for (File f : files) {
                if (f.isDirectory()) {
                    deleteFile(f);
                } else {
                    try {
                        f.delete();
                    } catch (Exception ignored) {}
                }
            }
            try {
                file.delete();
            } catch (Exception ignored) {
            }
        } catch (Exception ignored) {}
    }

    public static long getDirSize(File file) {
        if (file.exists()) {
            // 如果是目录则递归计算其内容的总大小
            if (file.isDirectory()) {
                File[] children = file.listFiles();
                long size = 0;
                if (children == null) return 0;
                for (File f : children)
                    size += getDirSize(f);
                return size;
            } else {
                // 如果是文件则直接返回其大小,以“兆”为单位
                return file.length();
            }
        } else {
            return 0;
        }
    }

    public static String readFileText(String filePath) throws IOException {
        File path = new File(filePath);
        // 此路径无文件
        if (!path.exists()) {
            throw new IOException("path not found: " + path.getAbsolutePath());
        } else if (path.isDirectory()) /*此文件是目录*/ {
            throw new IOException("Non-file type: " + path.getAbsolutePath());
        }
        StringBuilder stringBuilder = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new FileReader(filePath))) {
            String line;
            while ((line = reader.readLine()) != null) {
                stringBuilder.append(line);
                stringBuilder.append("\n");
            }
        }
        if (stringBuilder.length() >= 1) stringBuilder.deleteCharAt(stringBuilder.length() - 1);
        return stringBuilder.toString();
    }

    /**
     * 文件写入文本
     *
     * @param path     路径
     * @param content  内容
     * @param isAppend 是否追写 不是的话会覆盖
     */
    public static void writeTextToFile(String path, String content, boolean isAppend) {
        try {
            File file = new File(path);
            try {
                // 先创建文件夹
                if (!Objects.requireNonNull(file.getParentFile()).exists()) file.getParentFile().mkdirs();
                // 再创建文件 FileOutputStream 会自动创建文件但是不能创建多级目录
                if (!file.exists()) file.createNewFile();
            } catch (IOException ignored) {}
            try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(file, isAppend), StandardCharsets.UTF_8))) {
                writer.write(content);
            } catch (IOException ignored) {}
        } catch (Exception ignored) {}

    }

    public static void copyFile(String sourceFile, String targetPath) throws IOException {
        File file = new File(sourceFile);
        if (!file.exists()) {
            throw new IOException("path not found: " + file.getAbsolutePath());
        } else if (file.isDirectory()) {
            throw new IOException("Not a file, but a directory: " + file.getAbsolutePath());
        }
        copyFile(new FileInputStream(file), new File(targetPath));
    }

    public static void copyFile(File sourceFile, File target) throws IOException {
        copyFile(new FileInputStream(sourceFile), target);
    }

    public static void copyFileText(InputStream inputStream, File target) throws IOException {
        if (target == null) {
            throw new IOException("targetFile , Empty File object");
        }
        if (!target.exists()) {
            if (!Objects.requireNonNull(target.getParentFile()).exists()) {
                target.getParentFile().mkdirs();
            }
            if (!target.createNewFile()) {
                throw new IOException("create file fail: " + target.getAbsolutePath());
            }
        }
        StringBuilder builder = new StringBuilder();
        try (inputStream; BufferedReader sourceFileReader = new BufferedReader(new InputStreamReader(inputStream)); BufferedWriter destStream = new BufferedWriter(new FileWriter(target))) {
            String line;
            while ((line = sourceFileReader.readLine()) != null) {
                builder.append(line);
                builder.append("\n");
            }
            // 删除换行符
            if (builder.length() >= 1) builder.deleteCharAt(builder.length() - 1);
            destStream.write(builder.toString());
        }
    }

    public static void copyFile(InputStream inputStream, File target) throws IOException {
        if (target == null) {
            throw new IOException("targetFile , Empty File object");
        }
        if (!target.exists()) {
            if (!Objects.requireNonNull(target.getParentFile()).exists()) {
                target.getParentFile().mkdirs();
            }
            if (!target.createNewFile()) {
                throw new IOException("create file fail: " + target.getAbsolutePath());
            }
        }
        try (BufferedInputStream sourceFile = new BufferedInputStream(inputStream); BufferedOutputStream destStream = new BufferedOutputStream(new FileOutputStream(target))) {
            byte[] bytes = new byte[BYTE_SIZE];
            int len;
            while ((len = sourceFile.read(bytes)) != -1) {
                destStream.write(bytes, 0, len);
            }
            destStream.flush();
        }
    }


}
