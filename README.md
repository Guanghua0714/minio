# Minio 搭建图片服务器

## 环境准备
- linux operating system
- docker


## docker 安装 Minio 及配置


```shell
docker run -p 9000:9000 -p 9001:9001 --name minio -v /data/minio/data:/data -e MINIO_ROOT_USER=admin -e MINIO_ROOT_PASSWORD=you_password -d minio/minio server /data --console-address ":9001"
```
安装好之后浏览器输入 http://IP:9001 进入控制页面，输入上面定义的 **MINIO_ROOT_USER** 和 **MINIO_ROOT_PASSWORD**登录即可进入到控制页面


![image.png](https://p9-juejin.byteimg.com/tos-cn-i-k3u1fbpfcp/8dade263bbe94abd8e27c9194b8d9ecb~tplv-k3u1fbpfcp-watermark.image?)

然后创建一个 **bucket**

![image.png](https://p3-juejin.byteimg.com/tos-cn-i-k3u1fbpfcp/db552ca19722487a8c8bb1d15bad3c3d~tplv-k3u1fbpfcp-watermark.image?)

bucket创建好之后创建一个User(这里的公钥和私钥可以随便填写) **注意要赋予读写权限**


![image.png](https://p3-juejin.byteimg.com/tos-cn-i-k3u1fbpfcp/9007705188a14396b9b0dbf1dcfc6c7a~tplv-k3u1fbpfcp-watermark.image?)

进入到刚刚创建的user 下创建一个 service account

![image.png](https://p1-juejin.byteimg.com/tos-cn-i-k3u1fbpfcp/8918b258333545609520d425f3e14425~tplv-k3u1fbpfcp-watermark.image?)

如下图所示 将生成下来的密钥保存好 

![image.png](https://p1-juejin.byteimg.com/tos-cn-i-k3u1fbpfcp/59623c5780c44779880d7bcc7c871242~tplv-k3u1fbpfcp-watermark.image?)

## 编写代码

- pom.xml
```xml
<dependencies>
    <dependency>
        <groupId>io.minio</groupId>
        <artifactId>minio</artifactId>
        <version>8.3.0</version>
        <exclusions>
            <exclusion>
                <groupId>com.squareup.okhttp3</groupId>
                <artifactId>okhttp</artifactId>
            </exclusion>
        </exclusions>
    </dependency>

    <dependency>
        <groupId>com.squareup.okhttp3</groupId>
        <artifactId>okhttp</artifactId>
        <version>4.8.1</version>
    </dependency>

    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-web</artifactId>
        <version>2.5.6</version>
        <exclusions>
            <exclusion>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-starter-tomcat</artifactId>
            </exclusion>
        </exclusions>
    </dependency>
    
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-jetty</artifactId>
        <version>2.5.6</version>
    </dependency>

    <dependency>
        <groupId>commons-io</groupId>
        <artifactId>commons-io</artifactId>
        <version>2.11.0</version>
    </dependency>
</dependencies>
```


- application.yml
```yml
minio:
  # mino IP和端口
  endpoint: http://192.168.50.2:9000
  # bucket 名称
  bucket: test
  # 公钥
  access-key: TZB5PWJM72MAI2MKIG9D
  # 私钥
  secret-key: mPjaS+8FEPixY0tq6YeGbj4UFFdzEVuISjHEAmYQ

server:
  port: 8080
```

- MinIoClientConfig
```java
@Configuration
public class MinIoClientConfig {


    @Value("${minio.endpoint}")
    private String endpoint;
    @Value("${minio.access-key}")
    private String accessKey;
    @Value("${minio.secret-key}")
    private String secretKey;

    /**
     * 注入minio 客户端
     */
    @Bean
    public MinioClient minioClient() {
        return MinioClient.builder()
                .endpoint(endpoint)
                .credentials(accessKey, secretKey)
                .build();
    }
}
```
- MinioController
```

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
```
通过下面的html就能够对文件上传进行测试了，其他的直接通过浏览器测试就好了
- upload.html
```html
<form action="http://IP:PROT/files/upload" method="post" enctype="multipart/form-data">
    <p><input type="file" name="file"></p>
    <p><input type="submit" value="submit"></p>
</form>
```

[源代码放这啦](https://github.com/Guanghua0714/minio.git)

[上面打不开的话](https://gitee.com/gh_nom/minio.git)
