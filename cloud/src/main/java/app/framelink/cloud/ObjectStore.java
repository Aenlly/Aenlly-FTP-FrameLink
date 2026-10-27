package app.framelink.cloud;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;

/** 私有对象存储，下载始终经过项目授权，开发环境可用本地磁盘 */
@Component
public class ObjectStore {
    private final Path root;
    private final S3Client s3;
    private final String bucket;
    public ObjectStore(@Value("${app.storage-mode}") String mode, @Value("${app.storage-dir}") String dir,
                       @Value("${app.s3.endpoint}") String endpoint, @Value("${app.s3.region}") String region,
                       @Value("${app.s3.bucket}") String bucket, @Value("${app.s3.access-key}") String key,
                       @Value("${app.s3.secret-key}") String secret) {
        this.root = Path.of(dir).toAbsolutePath().normalize(); this.bucket = bucket;
        if ("s3".equals(mode)) {
            ApiProblem.require(!endpoint.isBlank() && !key.isBlank() && !secret.isBlank(), 500, "对象存储配置不完整");
            s3 = S3Client.builder().endpointOverride(URI.create(endpoint)).region(Region.of(region)).forcePathStyle(true)
                    .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(key, secret))).build();
        } else { ApiProblem.require("local".equals(mode), 500, "未知存储模式"); s3 = null; }
    }
    public void put(String key, InputStream data, long length, String mime) throws IOException {
        if (s3 != null) { s3.putObject(r -> r.bucket(bucket).key(key).contentType(mime), RequestBody.fromInputStream(data, length)); return; }
        Path file = path(key); Files.createDirectories(file.getParent()); Path part = file.resolveSibling(file.getFileName()+".part");
        try { Files.copy(data, part, java.nio.file.StandardCopyOption.REPLACE_EXISTING); ApiProblem.require(Files.size(part)==length, 400, "上传文件长度不符"); Files.move(part, file, java.nio.file.StandardCopyOption.ATOMIC_MOVE); }
        finally { Files.deleteIfExists(part); }
    }
    public InputStream read(String key) throws IOException { return s3 == null ? Files.newInputStream(path(key)) : s3.getObject(GetObjectRequest.builder().bucket(bucket).key(key).build()); }
    public void delete(String key) throws IOException { if (s3 == null) { Files.deleteIfExists(path(key)); } else { s3.deleteObject(r -> r.bucket(bucket).key(key)); } }
    private Path path(String key) { Path path = root.resolve(key).normalize(); ApiProblem.require(path.startsWith(root), 400, "资源路径无效"); return path; }
}
