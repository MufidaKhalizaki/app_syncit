package com.feality.app.syncit;

import android.app.Activity;
import android.app.Fragment;
import android.app.FragmentManager;
import android.app.ProgressDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pInfo;
import android.net.wifi.p2p.WifiP2pManager;
import android.os.Bundle;
import android.os.Handler;
import android.widget.Toast;

import java.net.InetAddress;


public class LaunchActivity extends Activity implements WifiP2pActionListener {
    private static final String LOG_TAG = LaunchActivity.class.getSimpleName();

    public static final String FRAGMENT_DISCOVERY = "fragment_discovery";
    public static final int DEFAULT_PORT = 8081;

    private final IntentFilter mP2pIntentFilter = new IntentFilter();
    private final IntentFilter mServiceIntentFilter = new IntentFilter();

    private ServiceIntentReceiver mServiceIntentReceiver;

    private PeerToPeerHandler mPeerToPeerHandler;

    private WifiP2pManager mP2PManager;
    private WifiP2pManager.Channel mChannel;
    private FragmentManager mFragmentManager;
    private ProgressDialog mProgressDialog;

    public <T> T get(int id, Class<T> clz){
        return clz.cast(findViewById(id));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        mFragmentManager = getFragmentManager();

        mFragmentManager
                .beginTransaction()
                .replace(R.id.main_fragment_placeholder, Fragment.instantiate(this, DiscoveryFragment.class.getName()), FRAGMENT_DISCOVERY)
                .setCustomAnimations(android.R.anim.fade_in, android.R.anim.fade_out)
                .commit();

        initWifiP2PReceivers();
    }

    private void initWifiP2PReceivers() {
        mP2PManager = WifiP2pManager.class.cast(getSystemService(WIFI_P2P_SERVICE));

        //  Indicates a change in the Wi-Fi P2P status.
        mP2pIntentFilter.addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION);
        // Indicates a change in the list of available mPeers.
        mP2pIntentFilter.addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION);
        // Indicates the state of Wi-Fi P2P connectivity has changed.
        mP2pIntentFilter.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION);
        // Indicates this device's details have changed.
        mP2pIntentFilter.addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION);

        // Indicates that a client have connected
        mServiceIntentFilter.addAction(ServiceIntentReceiver.ACTION_ON_CONNECTION);

        mChannel = mP2PManager.initialize(this, getMainLooper(), null);

        startServer();
    }

    private void startServer() {
        startService(SyncServer.IntentBuilder.start(this, DEFAULT_PORT));
    }

    /** register the BroadcastReceiver with the intent values to be matched */
    @Override
    public void onResume() {
        super.onResume();

        final DiscoveryFragment fragment = DiscoveryFragment.class.cast(
            mFragmentManager.findFragmentByTag(FRAGMENT_DISCOVERY)
        );

        mPeerToPeerHandler = new PeerToPeerHandler(mP2PManager, mChannel, fragment, this);
        registerReceiver(mPeerToPeerHandler, mP2pIntentFilter);
        //mPeerToPeerHandler.registerService();
        mPeerToPeerHandler.discoverPeers();

        mServiceIntentReceiver = new ServiceIntentReceiver();
        registerReceiver(mServiceIntentReceiver, mServiceIntentFilter);
    }

    @Override
    public void onPause() {
        mPeerToPeerHandler.tearDown();
        unregisterReceiver(mPeerToPeerHandler);
        unregisterReceiver(mServiceIntentReceiver);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        startService(SyncServer.IntentBuilder.stop(this));
        startService(SyncClient.IntentBuilder.stop(this));
        super.onDestroy();
    }

    public void distributeData(final Uri uri) {
        startService(SyncClient.IntentBuilder.send(this, uri));
    }

    public void connectServiceClientTo(InetAddress address) {
        startService(SyncClient.IntentBuilder.start(this, address.getHostAddress(), DEFAULT_PORT));
    }

    @Override
    public void onServiceDetected(final NsdServiceInfo serviceP2pDevice) {
        mProgressDialog.setMessage("Service detected" +
            "\nname: " + serviceP2pDevice.getServiceName() +
            "\ntype: " + serviceP2pDevice.getServiceType() +
            "\nhost: "+ serviceP2pDevice.getHost() + ":" + serviceP2pDevice.getPort());
        mProgressDialog.dismiss();
    }

    @Override
    public void onConnectedToDevice(final WifiP2pInfo info) {
        if (mProgressDialog == null) {
            mProgressDialog = ProgressDialog.show(this, "", "", true, false);
        }

        if (info.groupFormed) {
            if (info.isGroupOwner){
                mProgressDialog.setTitle("I´m group owner");
                mProgressDialog.setMessage(
                        "Waiting for incoming connection..." +
                        "\nip: " + info.groupOwnerAddress);
                // Wait for incoming connection
            } else {
                mProgressDialog.setTitle("Joined group");
                mProgressDialog.setMessage(
                        "Connecting to group owner..." +
                        "\nip: " + info.groupOwnerAddress);
                connectServiceClientTo(info.groupOwnerAddress);
            }
        } else {
            mProgressDialog.setTitle("Waiting for group");
            mProgressDialog.setMessage("Waiting for group to be formed...");
        }
    }

    @Override
    public void onDeviceSelected(final WifiP2pDevice p2pDevice) {
        mPeerToPeerHandler.connectToP2pDevice(p2pDevice);
        String name = String.format("%s (%s)", p2pDevice.deviceName, p2pDevice.deviceAddress);
        mProgressDialog = ProgressDialog.show(this, "Connecting",
                "Connecting to device " + name, true, false);
    }

    public static class ServiceIntentReceiver extends BroadcastReceiver {
        public final static String ACTION_ON_CONNECTION = "ServiceIntentReceiver.ACTION_ON_CONNECTION";
        public final static String EXTRA_MESSAGE = "ServiceIntentReceiver.EXTRA_MESSAGE";

        @Override
        public void onReceive(final Context context, final Intent intent) {
            String action = intent.getAction();

            if (ACTION_ON_CONNECTION.equals(action)) {
                String message = intent.getStringExtra(EXTRA_MESSAGE);
                Toast.makeText(context, message, Toast.LENGTH_LONG).show();
            }
        }
    }

}
