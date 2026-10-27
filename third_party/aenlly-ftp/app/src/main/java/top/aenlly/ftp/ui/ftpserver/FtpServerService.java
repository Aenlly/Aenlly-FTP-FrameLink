package top.aenlly.ftp.ui.ftpserver;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import androidx.core.app.NotificationCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import lombok.extern.slf4j.Slf4j;
import org.apache.ftpserver.FtpServer;
import org.apache.ftpserver.FtpServerConfigurationException;
import org.apache.ftpserver.FtpServerFactory;
import org.apache.ftpserver.command.Command;
import org.apache.ftpserver.command.CommandFactoryFactory;
import org.apache.ftpserver.ftplet.Authority;
import org.apache.ftpserver.ftplet.FtpException;
import org.apache.ftpserver.ftplet.UserManager;
import org.apache.ftpserver.listener.ListenerFactory;
import org.apache.ftpserver.usermanager.Md5PasswordEncryptor;
import org.apache.ftpserver.usermanager.PropertiesUserManagerFactory;
import org.apache.ftpserver.usermanager.impl.BaseUser;
import org.apache.ftpserver.usermanager.impl.WritePermission;
import org.apache.ftpserver.usermanager.impl.WriteRequest;
import top.aenlly.ftp.R;
import top.aenlly.ftp.command.STOR;
import top.aenlly.ftp.properties.FtpServerProperties;

import java.util.LinkedList;
import java.util.Map;

@Slf4j
public class FtpServerService extends Service {

    private FtpServer server;

    public FtpServerService() {
    }

    private final IBinder binder = new LocalBinder();

    public class LocalBinder extends Binder {
        FtpServerService getService() {
            // 返回当前服务实例，允许客户端调用服务中的公共方法
            return FtpServerService.this;
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // 实现前台进程保活
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    "ftp_service_channel_id",
                    "FTP Service Channel",
                    NotificationManager.IMPORTANCE_LOW
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.createNotificationChannel(channel);
        }
        try {
            startFtp();
        } catch (FtpException e) {
            throw new RuntimeException(e);
        }

        Notification notification = new NotificationCompat.Builder(this, "ftp_service_channel_id")
                .setContentTitle("FTP Server")
                .setContentText(FtpServerProperties.host + ":" + FtpServerProperties.port + " 正在运行中...")
                .setSmallIcon(R.mipmap.ic_launcher)
                .setOngoing(true)
                .build();
        startForeground(ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,notification);
        log.info("FTP Server started");
        return START_STICKY;
    }

    @Override
    public void onCreate() {
        super.onCreate();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        server.stop();
    }

    @SuppressLint("ResourceAsColor")
    private void startFtp() throws FtpException {

        boolean success = false;

        FtpServerFactory serverFactory = new FtpServerFactory();
        // 设置用户管理器
        PropertiesUserManagerFactory userManagerFactory = new PropertiesUserManagerFactory();
        userManagerFactory.setPasswordEncryptor(new Md5PasswordEncryptor());

        UserManager userManager = userManagerFactory.createUserManager();
        // 设置用户/密码/目录
        BaseUser user = new BaseUser();
        user.setName(FtpServerProperties.username);
        user.setPassword(FtpServerProperties.password);
        user.setHomeDirectory(FtpServerProperties.remoteDir);
        user.authorize(new WriteRequest());
        LinkedList<Authority> authorities = new LinkedList<>();
        authorities.add(new WritePermission());
        user.setAuthorities(authorities);
        userManager.save(user);
        serverFactory.setUserManager(userManager);
        // 创建监听器
        ListenerFactory factory = new ListenerFactory();
        factory.setPort(FtpServerProperties.port);
        factory.setServerAddress(FtpServerProperties.host);
        // 向服务器添加监听器
        serverFactory.addListener("default", factory.createListener());
        // 重写命令执行
        CommandFactoryFactory commandFactoryFactory = new CommandFactoryFactory();
        Map<String, Command> commandMap = commandFactoryFactory.getCommandMap();
        commandMap.put("STOR", new STOR(this.getApplicationContext()));

        serverFactory.setCommandFactory(commandFactoryFactory.createCommandFactory());

        // 设置文件系统的字符编码为 UTF-8

        // 创建FTP服务器
        server = serverFactory.createServer();

        // 启动FTP服务器
        try {
            server.start();
            success = true;
        } catch (FtpServerConfigurationException | FtpException e) {
            log.error("Failed to start",e);
        }

        // 发送广播
        Intent intent = new Intent("ftp-server-service");
        intent.putExtra("success", success);
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
    }

}