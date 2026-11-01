package io.github.sjcud.stockagent.rag.parser;

import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

import javax.imageio.ImageIO;

/** 本地Tesseract适配器：无shell拼接，限时/限输出，结束或中断均清理本次临时文件。 */
public final class TesseractOcrEngine implements OcrEngine {
    private final String executable, languages;
    private final Path dataPath;
    private final boolean available;

    /** 命令由服务端配置，语言仅允许Tesseract标识字符，不接受命令行参数。 */
    public TesseractOcrEngine(String executable, String languages, String dataPath) {
        if (executable == null
                || executable.trim().isEmpty()
                || languages == null
                || !languages.matches("[a-zA-Z0-9_+]+"))
            throw new IllegalArgumentException("OCR配置无效");
        this.executable = executable;
        this.languages = languages;
        Path local = Paths.get("data/ocr");
        this.dataPath =
                dataPath != null && !dataPath.trim().isEmpty()
                        ? Paths.get(dataPath)
                        : Files.isDirectory(local) ? local : null;
        this.available = probe();
    }

    @Override
    public boolean available() {
        return available;
    }

    /** 只检查可执行程序和所需语言名，不把本地路径返回浏览器。 */
    private boolean probe() {
        Path output = null;
        Process process = null;
        try {
            output = Files.createTempFile("ocr-capability-", ".txt");
            List<String> command = new ArrayList<String>(Arrays.asList(executable, "--list-langs"));
            if (dataPath != null) {
                command.add("--tessdata-dir");
                command.add(dataPath.toAbsolutePath().toString());
            }
            process =
                    new ProcessBuilder(command)
                            .redirectErrorStream(true)
                            .redirectOutput(output.toFile())
                            .start();
            if (!process.waitFor(2, TimeUnit.SECONDS)
                    || process.exitValue() != 0
                    || Files.size(output) > 65536) return false;
            List<String> installed = Files.readAllLines(output, StandardCharsets.UTF_8);
            for (String lang : languages.split("\\+")) if (!installed.contains(lang)) return false;
            return true;
        } catch (Exception ex) {
            if (ex instanceof InterruptedException) Thread.currentThread().interrupt();
            return false;
        } finally {
            if (process != null && process.isAlive()) process.destroyForcibly();
            if (output != null)
                try {
                    Files.deleteIfExists(output);
                } catch (java.io.IOException ignored) {
                }
        }
    }

    /** 保留stdout文本与stderr分离，错误诊断不混入研究证据。 */
    @Override
    public String recognize(BufferedImage image, long timeoutMillis) {
        if (!available) throw new IllegalArgumentException("OCR引擎或语言包不可用，请检查服务端OCR配置");
        if (image == null
                || (long) image.getWidth() * image.getHeight() > 20000000
                || timeoutMillis <= 0) throw new IllegalArgumentException("OCR图片或时限无效");
        Path dir = null;
        Process process = null;
        try {
            dir = Files.createTempDirectory("stock-ocr-");
            Path input = dir.resolve("page.png"),
                    output = dir.resolve("result.txt"),
                    error = dir.resolve("error.txt");
            ImageIO.write(image, "png", input.toFile());
            List<String> command =
                    new ArrayList<String>(
                            Arrays.asList(
                                    executable,
                                    input.toString(),
                                    "stdout",
                                    "-l",
                                    languages,
                                    "--oem",
                                    "1",
                                    "--psm",
                                    "6"));
            if (dataPath != null) {
                command.add("--tessdata-dir");
                command.add(dataPath.toAbsolutePath().toString());
            }
            process =
                    new ProcessBuilder(command)
                            .redirectOutput(output.toFile())
                            .redirectError(error.toFile())
                            .start();
            long deadline =
                    System.nanoTime()
                            + TimeUnit.MILLISECONDS.toNanos(Math.min(timeoutMillis, 30000L));
            while (!process.waitFor(50, TimeUnit.MILLISECONDS)) {
                if (System.nanoTime() >= deadline
                        || Files.size(output) > 4000000
                        || Files.size(error) > 65536)
                    throw new IllegalArgumentException("OCR超过时限或输出上限，请拆分文档");
            }
            if (process.exitValue() != 0)
                throw new IllegalArgumentException("OCR引擎或语言包不可用，请检查服务端OCR配置");
            if (Files.size(output) > 4000000) throw new IllegalArgumentException("OCR输出超过限制");
            return new String(Files.readAllBytes(output), StandardCharsets.UTF_8).trim();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("OCR已中断");
        } catch (java.io.IOException ex) {
            throw new IllegalArgumentException("OCR引擎不可用，请配置Tesseract及语言包");
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
                try {
                    process.waitFor(2, TimeUnit.SECONDS);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            }
            if (dir != null)
                for (String file : Arrays.asList("page.png", "result.txt", "error.txt", ""))
                    try {
                        Files.deleteIfExists(file.isEmpty() ? dir : dir.resolve(file));
                    } catch (java.io.IOException ignored) {
                    }
        }
    }
}
