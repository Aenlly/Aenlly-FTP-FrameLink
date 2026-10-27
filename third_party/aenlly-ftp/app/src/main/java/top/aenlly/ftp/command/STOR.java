package top.aenlly.ftp.command;

import android.content.Context;
import android.media.MediaScannerConnection;
import lombok.extern.slf4j.Slf4j;
import org.apache.ftpserver.ftplet.FtpException;
import org.apache.ftpserver.ftplet.FtpRequest;
import org.apache.ftpserver.impl.FtpIoSession;
import org.apache.ftpserver.impl.FtpServerContext;
import top.aenlly.ftp.constant.FtpConstant;
import top.aenlly.ftp.properties.FtpServerProperties;
import top.aenlly.ftp.utils.images.ImageUtils;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;

/**
 * 重写默认的STOR方法，接收ftp客户端传过来的数据，执行实现自己所需的后处理
 *
 * @author Aenlly||tnw
 * @create 2024/04/18 11:46
 * @since 1.0.0
 */
@Slf4j
public class STOR extends org.apache.ftpserver.command.impl.STOR {

    private Context context;

    public STOR(Context context) {
        this.context = context;
    }

    @Override
    public void execute(FtpIoSession session, FtpServerContext context, FtpRequest request) throws IOException, FtpException {
        super.execute(session, context, request);
        String fileName = request.getArgument();
        // 获取文件的路径，session.getFileSystemView().getFile(fileName).getAbsolutePath()只是获取到/xx/xx的路径
        File file = (File) session.getFileSystemView().getFile(fileName).getPhysicalFile();
        String absolutePath = file.getAbsolutePath();
        // 刷新媒体管理，让图片/视频加入到媒体库中
        MediaScannerConnection.scanFile(this.context,
                new String[]{absolutePath},
                FtpConstant.FILE_FORMATS,
                (path, uri) -> {});
        if (FtpServerProperties.compressState && FtpServerProperties.imageFormat !=null && Arrays.stream(FtpServerProperties.imageFormat)
                .anyMatch(absolutePath::endsWith)) {
            // 压缩图片
            ImageUtils.syncCompress(this.context, file, FtpServerProperties.compressDir, new ImageUtils.DefaultOnCompressListener(this.context));
        }
    }
}
