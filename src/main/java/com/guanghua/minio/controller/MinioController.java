package com.guanghua.minio.controller;

import com.google.common.collect.Sets;
import io.minio.*;
import io.minio.http.Method;
import io.minio.messages.Item;
import org.apache.commons.io.IOUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import javax.servlet.http.HttpServletResponse;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.URLConnection;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Objects;
import java.util.UUID;

@RestController
@RequestMapping("/files")
public class MinioController {

    @Autowired
    private MinioClient minioClient;

    @Value("${minio.bucket}")
    private String bucket;

    /**
     * 可支持的 上传文件类型
     */
    private final HashSet<String> imageSet = Sets.newHashSet(".jpg", ".gif", ".png", ".ico", ".bmp");

    /**
     * 临时文件名称
     */
    private final String TEMP_FILE = "TEMP_FILE";


    /**
     * 获取所有的文件名
     */
    @GetMapping("/list")
    public String testMinio() {

        Iterable<Result<Item>> results = minioClient.listObjects(ListObjectsArgs.builder().bucket(bucket).build());
        ArrayList<String> strings = new ArrayList<>();

        results.forEach(r -> {
            try {
                Item item = r.get();
                strings.add(item.objectName());
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
        return strings.toString() + "\n";
    }

    /**
     * @param object 文件名称
     */
    @GetMapping("download/{object}")
    public void download(@PathVariable("object") String object, HttpServletResponse response) {
        try (InputStream stream = minioClient.getObject(
                GetObjectArgs
                        .builder()
                        .bucket(bucket)
                        .object(object)
                        .build()
        )) {
            response.addHeader(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=" + object);
            response.setContentType(URLConnection.guessContentTypeFromName(object));
            IOUtils.copy(stream, response.getOutputStream());
            response.flushBuffer();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * @param object 文件名称
     */
    @GetMapping("online/{object}")
    public void online(@PathVariable("object") String object, HttpServletResponse response) {
        try {
            String objectUrl = minioClient.getPresignedObjectUrl(
                    GetPresignedObjectUrlArgs
                            .builder()
                            .bucket(bucket)
                            .object(object)
                            .method(Method.GET)
                            .build());
            response.sendRedirect(objectUrl);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }


    /**
     * @param uploadFile 上传的文件名称
     */
    @PostMapping("/upload")
    public void upload1(@RequestParam("file") MultipartFile uploadFile, HttpServletResponse response) {

        if (uploadFile == null || uploadFile.getSize() <= 0) {
            throw new IllegalArgumentException("请上传文件");
        }

        // 获取原始文件名
        String fileName = Objects.requireNonNull(uploadFile.getOriginalFilename()).toLowerCase();

        // 截取文件的 后缀
        String fileType = fileName.substring(fileName.lastIndexOf("."));
        // 判断是否为图片类型
        if (!imageSet.contains(fileType)) {
            // 用户上传的不是图片
            throw new IllegalArgumentException("用户上传的不是图片");
        }

        // 上传的数据是否为恶意程序. 高度和宽度是否为null
        BufferedImage bufferedImage;
        try {
            bufferedImage = ImageIO.read(uploadFile.getInputStream());
        } catch (IOException e) {
            throw new IllegalArgumentException("文件读取异常！");
        }
        int width = bufferedImage.getWidth();
        int height = bufferedImage.getHeight();
        // 做一些条件限制 上传的文件必须要 符合什么条件
        if (width == 0 || height == 0) {
            throw new IllegalArgumentException("用户上图片不符合要求");
        }


        String uuid = UUID.randomUUID().toString().replace("-", "");
        String realFileName = uuid + fileType;
        try {
            // new 一个文件
            File file = new File(TEMP_FILE);
            //将文件写入到当前目录下，作为临时存放
            inputStreamToFile(uploadFile.getInputStream(), file);
            // FileInputStream 构造出来需要一个真实存在的文件 并且 file.length() 也需要一个有真实长度的文件
            // 应该是可以直接读取内存中的文件进行写入到 minio 的
            FileInputStream in = new FileInputStream(file);

            PutObjectArgs arg = PutObjectArgs.builder().bucket(bucket).object(realFileName).stream(in, file.length(), -1).contentType("image/jpeg").build();
            minioClient.putObject(arg);

            // 相应url 回前端
            String url = minioClient.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder().bucket(bucket).object(realFileName).method(Method.GET).build());
            response.sendRedirect(url);
        } catch (Exception e) {
            e.printStackTrace();
        }

    }


    // 将流写入到文件
    private static void inputStreamToFile(InputStream ins, File file) {
        try (OutputStream os = new FileOutputStream(file)) {
            int bytesRead;
            byte[] buffer = new byte[8192];
            while ((bytesRead = ins.read(buffer, 0, 8192)) != -1) {
                os.write(buffer, 0, bytesRead);
            }
            ins.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}