package com.example.android.wifianalyzer;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.PriorityQueue;
import java.util.Random;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.support.constraint.solver.widgets.ConstraintAnchor;
import android.support.v4.app.FragmentActivity;
import android.os.Bundle;
import android.util.Log;
import android.widget.Toast;

import android.support.v4.content.ContextCompat;
import android.view.View;
import android.widget.Button;
import android.widget.Switch;

import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.SupportMapFragment;
import com.google.android.gms.maps.UiSettings;
import com.google.android.gms.maps.model.BitmapDescriptor;
import com.google.android.gms.maps.model.CameraPosition;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.Marker;
import com.google.android.gms.maps.model.MarkerOptions;
import com.google.android.gms.maps.model.BitmapDescriptorFactory;
import com.google.android.gms.maps.model.Tile;
import com.google.android.gms.maps.model.TileOverlay;
import com.google.android.gms.maps.model.TileOverlayOptions;
import com.google.maps.android.heatmaps.HeatmapTileProvider;

import java.util.ArrayList;
import java.util.Random;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;
import java.util.Map;

public class MapsActivity extends FragmentActivity implements OnMapReadyCallback {
    private GoogleMap mMap;
    private Handler handler = new Handler();
    double latitude, longitude;
    double maxLat, maxLng;
    GPSTracker gps;
    LatLng curPos;

    private HeatmapTileProvider mProvider;
    private TileOverlay mOverlay;
    private Marker mMarker;

    private WifiManager mainWifi;
    private Map<String, Signal> wifiSignals;
    private Map<String,Signal> newWifiSignals;
    private SignalReceiver signalReceiver;
    private PriorityQueue<SignalInfo> pq;

    // Enable Satelite View by Xintian
    void satelliteBtn() {
        final Switch switch1 = findViewById(R.id.satelliteBtn);
        switch1.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (switch1.isChecked())
                    mMap.setMapType(GoogleMap.MAP_TYPE_HYBRID);
                else
                    mMap.setMapType(GoogleMap.MAP_TYPE_NORMAL);
            }
        });
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_maps);
        SupportMapFragment mapFragment = (SupportMapFragment) getSupportFragmentManager()
                .findFragmentById(R.id.map);
        mapFragment.getMapAsync(this);
        satelliteBtn();
    }

    // Heatmap feature by Yingkai
    private HeatmapTileProvider getHeatMap() {
        ArrayList<LatLng> list = new ArrayList<LatLng>();
        int maxSignal = Integer.MIN_VALUE;
        for (SignalInfo info : pq) {
            double lat = info.latitude;
            double lng = info.longitude;
            if (info.strength > maxSignal) {
                maxLat = lat;
                maxLng = lng;
            }
            list.add(new LatLng(lat, lng));
        }
        Log.i("WifiJsn", list.toString());
        mProvider = new HeatmapTileProvider.Builder()
                .data(list)
                .build();
        return mProvider;
    }

    @Override
    public void onMapReady(GoogleMap googleMap) {
        mMap = googleMap;

        setSignalReceiver();
        startSignalSearch();

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED) {
            mMap.setMyLocationEnabled(true);
        }

        gps = new GPSTracker (MapsActivity.this);
        latitude = gps.getLatitude();
        longitude= gps.getLongitude();
        curPos = new LatLng(latitude, longitude);
        wifiSignals = new HashMap<>();
        newWifiSignals = new HashMap<>();

        pq = new PriorityQueue<>(100,
                new Comparator<SignalInfo>() {
                    @Override
                    public int compare(SignalInfo t1, SignalInfo t2) {
                        if (t1.strength - t2.strength > 0)
                            return 1;
                        else if (t1.strength - t2.strength < 0)
                            return -1;

                        return 0;
                    }
                });

        CameraPosition cameraPosition = new CameraPosition.Builder()
                .target(curPos)
                .zoom(18)
                .build();
        mMap.animateCamera(CameraUpdateFactory.newCameraPosition(cameraPosition));

        threeDBtn(curPos);
        startBtn();
    }

    // Contain signal information by Yingkai & Xinyue
    public class SignalInfo implements Comparable {
        String wifiId;
        String wifiName;
        int strength;
        double latitude;
        double longitude;

        public SignalInfo(String wifiId, String wifiName, int strength, double latitude, double longitude) {
            this.wifiId = wifiId;
            this.wifiName = wifiName;
            this.strength = strength;
            this.latitude = latitude;
            this.longitude = longitude;
        }

        @Override
        public String toString() {
            return wifiId + " - " + wifiName + " - " + latitude + ", " + longitude + " - " + strength;
        }

        @Override
        public int compareTo(Object o) {
            SignalInfo otherSignal = (SignalInfo) o;
            return otherSignal.strength - strength;
        }
    }

    // Get strongest 100 singal strength sources by Yingkai & Xinyue
    public void getBestKLoc(Map<String, Signal> map, PriorityQueue<SignalInfo> pq) {
            String wifiId = "";
            String wifiName = "";
            int maxSt = Integer.MIN_VALUE;
            if (pq.size() < 1) {
                //Log.i("func2", "pq size <1:pqsize" + pq.size());
                for (Map.Entry<String, Signal> entry : map.entrySet()) {
                    if (entry.getValue().getStrength() > maxSt) {
                        maxSt = entry.getValue().getStrength();
                        wifiId = entry.getKey();
                        wifiName = entry.getValue().getName();
                    }
                }
                pq.add(new SignalInfo(wifiId, wifiName, maxSt, latitude, longitude));
                //Log.i("func2Name:", "pq:" + pq.peek().wifiname);

            } else {
                for (Map.Entry<String, Signal> entry : map.entrySet()) {
                    if (pq.size() < 100 && pq.peek().wifiId == entry.getKey()) {
//                        longitude = gps.getLongitude();
//                        latitude = gps.getLatitude();
                        maxSt = entry.getValue().getStrength();
                        wifiId = entry.getKey();
                        wifiName = entry.getValue().getName();
                        pq.add(new SignalInfo(wifiId, wifiName, maxSt, latitude, longitude));
                        //continue;
                    } else if (pq.size() == 100 && pq.peek().wifiId == entry.getKey()) {
                        int peekStrength = pq.peek().strength;
                        if (peekStrength < entry.getValue().getStrength()) {
                            pq.poll();
//                            longitude = gps.getLongitude();
//                            latitude = gps.getLatitude();
                            maxSt = entry.getValue().getStrength();
                            wifiId = entry.getKey();
                            wifiName = entry.getValue().getName();
                            pq.add(new SignalInfo(wifiId, wifiName, maxSt, latitude, longitude));
                            //continue;
                        }
                    }
                }
            }

            // Test elements in priority queue
            Iterator it = pq.iterator();
            while (it.hasNext()) {
                Log.i("WifiInfo ", it.next().toString());
            }
        }

    // Enable 3D view by Xintian
    public void threeDBtn (final LatLng position) {
        final Switch switch2 = findViewById(R.id.threeDBtn);
        switch2.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (switch2.isChecked()) {
                    CameraPosition cameraPosition = new CameraPosition.Builder()
                            .target(position)
                            .zoom(19)
                            .bearing(45)
                            .tilt(90)                   // Tilt can only be 0 - 90
                            .build();
                    mMap.animateCamera(CameraUpdateFactory.newCameraPosition(cameraPosition));
                } else {
                    CameraPosition cameraPosition = new CameraPosition.Builder()
                            .target(position)
                            .zoom(18)
                            .build();
                    mMap.animateCamera(CameraUpdateFactory.newCameraPosition(cameraPosition));
                }
            }
        });
    }

    // Enable start Button by Xinyue
    public void startBtn() {
        final Button mark = findViewById(R.id.startBtn);
        mark.setOnClickListener(new View.OnClickListener() {
//            File file = new File("file://Users/xintian/Desktop/flag.bmp");
//            Bitmap bit = BitmapFactory.decodeFile(String.valueOf(file));
            @Override
            public void onClick(View view) {
                if(mark.getText().equals("Start")) {
                    mark.setText("Stop");
                    if(mOverlay != null) {
                        mOverlay.remove();
                    }
                    if (mMarker != null) {
                        mMarker.remove();
                    }
                    // Updating all signals every other second
                    Runnable runnable = new Runnable() {
                        @Override
                        public void run() {
                            gps = new GPSTracker(MapsActivity.this);
                            latitude = gps.getLatitude();
                            longitude = gps.getLongitude();
                            curPos = new LatLng(latitude, longitude);
                            wifiSignals = updateWifi();
                            getBestKLoc(wifiSignals, pq);
                            handler.postDelayed(this, 1000);
                        }
                    };
                    handler.postDelayed(runnable, 1000);
                }
                else {
                    mark.setText("Start");
                    mOverlay = mMap.addTileOverlay(new TileOverlayOptions().tileProvider(getHeatMap()));
                    mMarker = mMap.addMarker(new MarkerOptions().position(new LatLng(maxLat, maxLng))
                            .title("Best WiFi!"));
//                    mMap.addMarker(new MarkerOptions().icon(BitmapDescriptorFactory.fromBitmap(bit))
//                            .position(new LatLng(maxLat, maxLng))
//                            .title("Best WiFi!"));

                }
            }
        });
    }

    // From MainAcitivity to initiliza wifi search by Xintian
    public HashMap<String, Signal> updateWifi() {
        HashMap<String, Signal> updatedWifi = new HashMap<>();
        for (Signal signal : wifiSignals.values()) {
            if (newWifiSignals.containsKey(signal.getId())) {
                updatedWifi.put(signal.getId(), signal);
            }
        }
        for (Signal signal : newWifiSignals.values()) {
            if (updatedWifi.containsKey(signal.getId())) {
                Signal cur = updatedWifi.get(signal.getId());
                cur.update(signal.getLevel());
            } else {
                updatedWifi.put(signal.getId(), signal);
            }
        }
        return updatedWifi;
    }

    public void wifiReceived() {
        //Log.i(wifiTag, "\n ========wifi search complete========== \n");

        HashMap<String, Signal> tempWifiSignals = new HashMap<>();
        List<ScanResult> wifiScanResults = mainWifi.getScanResults();
        for (ScanResult wifi: wifiScanResults) {
            Signal wifiSignal = new Signal(wifi);
            tempWifiSignals.put(wifi.BSSID, wifiSignal);
            //Log.i(wifiTag,wifi.SSID + " - " + wifi.BSSID + " - " + wifi.level);
        }
        newWifiSignals = tempWifiSignals;
        //Log.i(wifiTag, "Starting another search again");

        mainWifi.startScan();
    }

    /*
    converts all received wifi objects to signal objects
    updates the list of received wifi signals
    starts another wifi scan
    */
    public class SignalReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (WifiManager.SCAN_RESULTS_AVAILABLE_ACTION.equals(action)) {
                wifiReceived();
            }
        }
    }

    /* set up broadcast receiver to identify signals */
    public void setSignalReceiver() {
        signalReceiver = new SignalReceiver();
        IntentFilter signalIntent = new IntentFilter();
        signalIntent.addAction(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION);
        signalIntent.addAction(BluetoothDevice.ACTION_FOUND);
        signalIntent.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
        registerReceiver(signalReceiver, signalIntent);
    }

    /* start seaching for signals in area signals */
    public void startSignalSearch() {
        // for wifi
        mainWifi = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
        if(mainWifi.isWifiEnabled()) {
            mainWifi.startScan();
        }
    }
}
