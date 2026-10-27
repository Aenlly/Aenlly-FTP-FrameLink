package top.aenlly.ftp.ui.ftpserver;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import lombok.extern.slf4j.Slf4j;
import top.aenlly.ftp.R;
import top.aenlly.ftp.constant.CacheConstant;
import top.aenlly.ftp.databinding.FragmentFtpServerBinding;
import top.aenlly.ftp.properties.FtpServerProperties;
import top.aenlly.ftp.utils.cache.SharedPreferencesUtils;

import java.io.File;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.Enumeration;

@Slf4j
public class FtpServerFragment extends Fragment {


    private ActivityResultLauncher<Intent> uploadLauncher;

    private ActivityResultLauncher<Intent> compressLauncher;

    private FragmentFtpServerBinding binding;

    private Context context;

    /**
     * FTP服务器广播接收器
     */
    private final BroadcastReceiver ftpServerReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            // 广播接收处理
            boolean isStart = intent.getBooleanExtra("success", false);
            if (isStart) {
                startUp();
                return;
            }
            binding.tvTooltip.setText("启动失败：请检查端口是否被占用和配置是否已经填写完整");
            Intent ftpIntent = new Intent(context, FtpServerService.class);
            context.stopService(ftpIntent);
        }
    };

    private void startUp() {
        binding.tvTooltip.setText("已启用:" + FtpServerProperties.host + ":" + FtpServerProperties.port);
        binding.btnStart.setVisibility(View.GONE);
        binding.btnStop.setVisibility(View.VISIBLE);
        binding.tvTooltip2.setText("");
    }

    public View onCreateView(@NonNull LayoutInflater inflater,
            ViewGroup container, Bundle savedInstanceState) {
        binding = FragmentFtpServerBinding.inflate(inflater, container, false);

        return binding.getRoot();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
    }

    public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
        context = view.getContext();
        SharedPreferencesUtils.init(context);
        bindCache();
        registerForActivityResult();
        registerForBtn();
        super.onViewCreated(view, savedInstanceState);
    }

    @Override
    public void onStart() {
        // 注册广播通知
        LocalBroadcastManager.getInstance(context).registerReceiver(ftpServerReceiver, new IntentFilter("ftp-server-service"));
        super.onStart();
    }

    void registerForBtn() {
        binding.etDataDir.setOnClickListener(view1 -> {
            // 启动Activity并处理结果
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            uploadLauncher.launch(intent);
        });
        binding.etCompressDir.setOnClickListener(view1 -> {
            // 启动Activity并处理结果
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            compressLauncher.launch(intent);
        });

        Intent ftpIntent = new Intent(context, FtpServerService.class);
        binding.btnStart.setOnClickListener(view1 -> {
            initProperties();
            if (!verifyParameter()) {
                return;
            }
            context.startService(ftpIntent);
        });

        binding.btnStop.setOnClickListener(view1 -> {
            context.stopService(ftpIntent);
            binding.tvTooltip.setText("未启用");
            SharedPreferencesUtils.putString(CacheConstant.START_STOP, binding.btnStart.getText().toString());
            binding.btnStart.setVisibility(View.VISIBLE);
            binding.btnStop.setVisibility(View.GONE);
            binding.tvTooltip2.setText("需要开启热点使用");
        });

        if(FtpServerProperties.host != null ){
            startUp();
        }
    }

    /**
     * 注册查看活动结果，用于选择目录并填充路径到组件上显示，避免使用者输入错误
     */
    void registerForActivityResult() {
        // 在Activity中定义一个ActivityResultLauncher
        uploadLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == Activity.RESULT_OK) {
                        Uri treeUri = null;
                        if (result.getData() != null) {
                            treeUri = result.getData().getData();
                        }
                        File directory = Environment.getExternalStorageDirectory();
                        String directoryPath = treeUri.getPath(); // 这是选定的文件目录路径
                        directoryPath = directoryPath.replace("/tree/primary:", directory.getAbsolutePath() + "/");
                        // 将路径显示在EditText中或者做其他操作
                        binding.etDataDir.setText(directoryPath);
                    }
                });
        compressLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == Activity.RESULT_OK) {
                        Uri treeUri = null;
                        if (result.getData() != null) {
                            treeUri = result.getData().getData();
                        }
                        File directory = Environment.getExternalStorageDirectory();
                        String directoryPath = treeUri.getPath(); // 这是选定的文件目录路径
                        directoryPath = directoryPath.replace("/tree/primary:", directory.getAbsolutePath() + "/");
                        // 将路径显示在EditText中或者做其他操作
                        binding.etCompressDir.setText(directoryPath);
                    }
                });
    }


    /**
     * 获取本地 IP 地址
     *
     * @return {@link String}
     */
    private String getLocalIpAddress() {
        try {
            Enumeration<NetworkInterface> enumNetworkInterfaces = NetworkInterface.getNetworkInterfaces();
            while (enumNetworkInterfaces.hasMoreElements()) {
                NetworkInterface networkInterface = enumNetworkInterfaces.nextElement();
                Enumeration<InetAddress> enumInetAddress = networkInterface.getInetAddresses();
                while (enumInetAddress.hasMoreElements()) {
                    InetAddress inetAddress = enumInetAddress.nextElement();
                    if (!inetAddress.isLoopbackAddress() && inetAddress.getAddress().length == 4) {
                        // 找到了IPv4地址
                        return inetAddress.getHostAddress();
                    }
                }
            }
        } catch (SocketException ex) {
            log.error("SocketException: ",ex);
        }
        return null;
    }

    /**
     * init 属性
     */
    void initProperties() {
        FtpServerProperties.username = binding.etUsername.getText().toString();
        FtpServerProperties.password = binding.etPassword.getText().toString();
        if(binding.etPort.getText().length() > 0 ){
            FtpServerProperties.port = Integer.parseInt(binding.etPort.getText().toString());
        }
        FtpServerProperties.remoteDir = binding.etDataDir.getText().toString();
        FtpServerProperties.encoding = binding.etEncoding.getText().toString();
        FtpServerProperties.host = getLocalIpAddress();
        FtpServerProperties.compressDir = binding.etCompressDir.getText().toString();
        FtpServerProperties.compressState = binding.rdrgpState.getCheckedRadioButtonId() == R.id.rdbtn_true;
        FtpServerProperties.compressThumbState = binding.rdrgpThumbState.getCheckedRadioButtonId() == R.id.rdbtn_thumb_true;
        String[] format = binding.etImageFormat.getText().toString().split(",");
        FtpServerProperties.imageFormat = format.length != 0 && !binding.etImageFormat.getText().toString().isEmpty() ? format : null;
        FtpServerProperties.imageMul = binding.etEncoding.getText().toString().isEmpty()? 1 : Float.parseFloat(binding.etImageFormat.getText().toString());
        flushedCache();
    }

    /**
     * 绑定缓存
     */
    @SuppressLint("ResourceAsColor")
    void bindCache() {
        binding.btnStart.setBackgroundColor(R.color.blue);
        binding.etPort.setText(SharedPreferencesUtils.getString(CacheConstant.PORT));
        binding.etDataDir.setText(SharedPreferencesUtils.getString(CacheConstant.UPLOAD_DIR));
        binding.etUsername.setText(SharedPreferencesUtils.getString(CacheConstant.USER_NAME));
        binding.etPassword.setText(SharedPreferencesUtils.getString(CacheConstant.PASSWORD));
        binding.etEncoding.setText(SharedPreferencesUtils.getString(CacheConstant.ENCODING));
        binding.etCompressDir.setText(SharedPreferencesUtils.getString(CacheConstant.COMPRESS_DIR));
        binding.rdrgpState.check(SharedPreferencesUtils.getBoolean(CacheConstant.COMPRESS_STATE) ? R.id.rdbtn_true : R.id.rdbtn_false);
        binding.rdrgpThumbState.check(SharedPreferencesUtils.getBoolean(CacheConstant.COMPRESS_THUMB_STATE) ? R.id.rdbtn_thumb_true : R.id.rdbtn_thumb_false);
        binding.etImageFormat.setText(SharedPreferencesUtils.getString(CacheConstant.IMAGE_FORMAT));
        binding.etImageMul.setText(SharedPreferencesUtils.getString(CacheConstant.IMAGE_MUL));
    }

    /**
     * 刷新缓存
     */
    void flushedCache() {
        SharedPreferencesUtils.putString(CacheConstant.PORT, binding.etPort.getText().toString());
        SharedPreferencesUtils.putString(CacheConstant.UPLOAD_DIR, binding.etDataDir.getText().toString());
        SharedPreferencesUtils.putString(CacheConstant.USER_NAME, binding.etUsername.getText().toString());
        SharedPreferencesUtils.putString(CacheConstant.PASSWORD, binding.etPassword.getText().toString());
        SharedPreferencesUtils.putString(CacheConstant.ENCODING, binding.etEncoding.getText().toString());
        SharedPreferencesUtils.putString(CacheConstant.COMPRESS_DIR, binding.etCompressDir.getText().toString());
        SharedPreferencesUtils.putBoolean(CacheConstant.COMPRESS_STATE, binding.rdrgpState.getCheckedRadioButtonId() == R.id.rdbtn_true);
        SharedPreferencesUtils.putBoolean(CacheConstant.COMPRESS_THUMB_STATE, binding.rdrgpThumbState.getCheckedRadioButtonId() == R.id.rdbtn_thumb_true);
        SharedPreferencesUtils.putString(CacheConstant.IMAGE_FORMAT, binding.etImageFormat.getText().toString());
        SharedPreferencesUtils.putString(CacheConstant.IMAGE_MUL, binding.etImageMul.getText().toString());
    }

    boolean verifyParameter() {
        if (binding.etDataDir.getText().toString().isEmpty()) {
            binding.etDataDir.setError("请选择上传目录");
            return false;
        }
        if (binding.etUsername.getText().toString().isEmpty()) {
            binding.etUsername.setError("请填写账号");
            return false;
        }
        if (binding.etPassword.getText().toString().isEmpty()) {
            binding.etPassword.setError("请填写密码");
            return false;
        }
        if (binding.etPort.getText().toString().isEmpty()) {
            binding.etPort.setError("请填写端口");
            return false;
        }
        // if (binding.etEncoding.getText().toString().isEmpty()) {
        //     binding.etEncoding.setError("请填写编码");
        //     return false;
        // }
        if (FtpServerProperties.host == null) {
            Toast.makeText(context, "请先打开热点！", Toast.LENGTH_SHORT).show();
            return false;
        }
        if (FtpServerProperties.compressState && FtpServerProperties.compressDir.isEmpty()) {
            binding.etCompressDir.setError("压缩存储目录不能为空！");
            return false;
        }
        if (FtpServerProperties.compressState && FtpServerProperties.imageFormat == null) {
            binding.etImageFormat.setError("压缩匹配格式不能为空！");
            return false;
        }
        return true;
    }
}