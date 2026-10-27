package top.aenlly.ftp.ui.ftpclient;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import androidx.fragment.app.Fragment;
import org.apache.commons.net.ftp.FTPClient;
import top.aenlly.ftp.R;
import top.aenlly.ftp.properties.FtpClientProperties;

import java.io.IOException;

/**
 * A simple {@link Fragment} subclass.
 * Use the {@link FtpClientFragment#newInstance} factory method to
 * create an instance of this fragment.
 */
public class FtpClientFragment extends Fragment {

    private FTPClient ftpClient;

    // TODO: Rename parameter arguments, choose names that match
    // the fragment initialization parameters, e.g. ARG_ITEM_NUMBER
    private static final String ARG_PARAM1 = "param1";
    private static final String ARG_PARAM2 = "param2";

    // TODO: Rename and change types of parameters
    private String mParam1;
    private String mParam2;

    public FtpClientFragment() {
        // Required empty public constructor
    }

    /**
     * Use this factory method to create a new instance of
     * this fragment using the provided parameters.
     *
     * @param param1 Parameter 1.
     * @param param2 Parameter 2.
     * @return A new instance of fragment FtpClientFragment.
     */
    // TODO: Rename and change types and number of parameters
    public static FtpClientFragment newInstance(String param1, String param2) {
        FtpClientFragment fragment = new FtpClientFragment();
        Bundle args = new Bundle();
        args.putString(ARG_PARAM1, param1);
        args.putString(ARG_PARAM2, param2);
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getArguments() != null) {
            mParam1 = getArguments().getString(ARG_PARAM1);
            mParam2 = getArguments().getString(ARG_PARAM2);
        }
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
            Bundle savedInstanceState) {
        // Inflate the layout for this fragment
        return inflater.inflate(R.layout.fragment_ftp_client, container, false);
    }

    void startFtpClient() throws IOException {
        ftpClient = new FTPClient();
        ftpClient.connect(FtpClientProperties.host,FtpClientProperties.port);
        ftpClient.login(FtpClientProperties.username,FtpClientProperties.password);
    }
}