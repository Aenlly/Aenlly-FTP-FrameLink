package top.aenlly.ftp.utils.images;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaScannerConnection;
import lombok.extern.slf4j.Slf4j;
import top.aenlly.ftp.constant.FtpConstant;
import top.aenlly.ftp.properties.FtpServerProperties;
import top.zibin.luban.InputStreamProvider;
import top.zibin.luban.Luban;
import top.zibin.luban.OnCompressListener;

import java.io.*;

/**
 * @author Aenlly||tnw
 * @create 2024/04/22 14:24
 * @since 1.0.0
 */

public class ImageUtils {

    private ImageUtils() {
    }

    /**
     * 同步压缩
     *
     * @param context            上下文
     * @param photo             照片
     * @param targetPath         目标路径
     * @param onCompressListener 在压缩侦听器上
     */
    public static void syncCompress(Context context, File photo, String targetPath, OnCompressListener onCompressListener) {
        // 先尺寸压缩
        Bitmap bitmap = bitmapFactory(photo.getAbsolutePath());

        Luban.with(context)
                .load(new InputStreamProvider() {
                    @Override
                    public InputStream open() throws IOException {
                        ByteArrayOutputStream baos = new ByteArrayOutputStream();
                        // 转换流，并使用不压缩的方式，因为已经压缩过了
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 100, baos);
                        return new ByteArrayInputStream(baos.toByteArray());
                    }

                    @Override
                    public String getPath() {
                        return photo.getAbsolutePath();
                    }
                })
                // 未压缩的阈值
                .ignoreBy(1024 * 3)
                // 目标目录
                .setTargetDir(targetPath)
                // 压缩回调接口
                .setCompressListener(onCompressListener)
                // 重命名，使用原来的名称
                .setRenameListener(s -> {
                    String[] split = s.split("/");
                    return split[split.length - 1];
                })
                .launch();
    }

    /**
     * 尺寸压缩，大图片加载会导致oom
     * 压缩图片使用,采用BitmapFactory.decodeFile
     */
    private static Bitmap bitmapFactory(String path) {
        // 配置压缩的参数
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true; //获取当前图片的边界大小，而不是将整张图片载入在内存中，避免内存溢出
        BitmapFactory.decodeFile(path, options);
        options.inJustDecodeBounds = false;
        ////inSampleSize的作用就是可以把图片的长短缩小inSampleSize倍，所占内存缩小inSampleSize的平方
        options.inSampleSize = Math.round(computeSampleSize(options, 4000, 6000) * FtpServerProperties.imageMul);
        return BitmapFactory.decodeFile(path, options); // 解码文件
    }

    /**
     * 计算出所需要压缩的大小
     *
     * @param options
     * @param reqWidth  我们期望的图片的宽，单位px
     * @param reqHeight 我们期望的图片的高，单位px
     * @return
     */
    private static int computeSampleSize(BitmapFactory.Options options, int reqWidth, int reqHeight) {
        int sampleSize = 1;
        int picWidth = options.outWidth;
        int picHeight = options.outHeight;
        if (picWidth > reqWidth || picHeight > reqHeight) {
            int halfPicWidth = picWidth / 2;
            int halfPicHeight = picHeight / 2;
            while (halfPicWidth / sampleSize > reqWidth || halfPicHeight / sampleSize > reqHeight) {
                sampleSize *= 2;
            }
        }
        return sampleSize;
    }

    @Slf4j
    public static class DefaultOnCompressListener implements OnCompressListener {
        private final Context context;

        public DefaultOnCompressListener(Context context) {
            super();
            this.context = context;
        }

        @Override
        public void onStart() {
        }

        @Override
        public void onSuccess(File file) {
            // 刷新媒体管理，让图片/视频加入到媒体库中
            if (FtpServerProperties.compressThumbState) {
                MediaScannerConnection.scanFile(this.context,
                        new String[]{file.getParent()},
                        FtpConstant.FILE_FORMATS,
                        (path, uri) -> {});
            }
        }

        @Override
        public void onError(Throwable throwable) {
            log.error(throwable.getMessage());
        }
    }
}
