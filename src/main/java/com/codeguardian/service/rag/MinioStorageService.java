package com.codeguardian.service.rag;

import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.util.UUID;

import io.minio.RemoveObjectArgs;

@Service
@Slf4j
@RequiredArgsConstructor
public class MinioStorageService {

    private final MinioClient minioClient;

    @Value("${minio.bucket-name}")
    private String bucketName;

    /**
     * 删除文件
     * @param objectName 对象名称
     */
    public void removeFile(String objectName) {
        try {
            minioClient.removeObject(
                    RemoveObjectArgs.builder()
                            .bucket(bucketName)
                            .object(objectName)
                            .build());
            log.info("Removed file from MinIO: bucket={}, object={}", bucketName, objectName);
        } catch (Exception e) {
            log.error("Failed to remove file from MinIO", e);
            throw new RuntimeException("文件删除失败", e);
        }
    }

    /**
     * 上传文件到MinIO
     *
     * @param file 文件
     * @return 存储的对象名称
     */
    public String uploadFile(MultipartFile file) {
        try {
            // 检查存储桶是否存在，不存在则创建
            boolean found = minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucketName).build());
            if (!found) {
                minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucketName).build());
                log.info("Created MinIO bucket: {}", bucketName);
            }

            String originalFilename = file.getOriginalFilename();
            String extension = "";
            if (originalFilename != null && originalFilename.contains(".")) {
                extension = originalFilename.substring(originalFilename.lastIndexOf("."));
            }
            
            // 生成唯一文件名
            String objectName = UUID.randomUUID().toString() + extension;

            // 上传文件
            minioClient.putObject(
                    PutObjectArgs.builder()
                            .bucket(bucketName)
                            .object(objectName)
                            .stream(file.getInputStream(), file.getSize(), -1)
                            .contentType(file.getContentType())
                            .build());

            log.info("Uploaded file to MinIO: bucket={}, object={}", bucketName, objectName);
            return objectName;
        } catch (Exception e) {
            log.error("Failed to upload file to MinIO", e);
            throw new RuntimeException("文件上传失败", e);
        }
    }

    /**
     * 获取文件流
     *
     * @param objectName 对象名称
     * @return 文件输入流
     */
    public InputStream getFile(String objectName) {
        try {
            return minioClient.getObject(
                    GetObjectArgs.builder()
                            .bucket(bucketName)
                            .object(objectName)
                            .build());
        } catch (Exception e) {
            log.error("Failed to get file from MinIO: bucket={}, object={}", bucketName, objectName, e);
            throw new RuntimeException("获取文件失败", e);
        }
    }

    public String getBucketName() {
        return bucketName;
    }
}
