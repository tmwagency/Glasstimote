package com.advantej.glass.glasstimote.services;

import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.RemoteException;
import android.util.Log;
import android.widget.RemoteViews;
import android.widget.Toast;

import com.advantej.glass.glasstimote.activities.LiveCardMenuActivity;
import com.advantej.glass.glasstimote.R;
import com.advantej.glass.glasstimote.model.vo.LocationDataVO;
import com.advantej.glass.glasstimote.tasks.DataUpdateTask;
import com.estimote.sdk.Beacon;
import com.estimote.sdk.BeaconManager;
import com.estimote.sdk.Region;
import com.estimote.sdk.Utils;
import com.google.android.glass.timeline.LiveCard;

import org.apache.http.NameValuePair;
import org.apache.http.message.BasicNameValuePair;

import java.util.ArrayList;
import java.util.List;

public class GlasstimoteService extends Service {


    private static final String TAG = "munky";

    private static final String LIVE_CARD_TAG = "BEACONS_CARD";
    private static final int TMW_BEACONS_MAJOR = 200;
    private static final int CREATIVE_BEACON_MINOR = 1;
    private static final int KITCHEN_BEACON_MINOR = 2;
    private static final int TECH_BEACON_MINOR = 3;
    private static final double BEACON_REGION_ENTRY_DISTANCE = 0.8;
    private static final double BEACON_REGION_EXIT_DISTANCE = 1.8;
    private static final Region CREATIVE_BEACON_REGION = new Region("creative", null, TMW_BEACONS_MAJOR, CREATIVE_BEACON_MINOR);
    private static final Region KITCHEN_BEACON_REGION = new Region("kitchen", null, TMW_BEACONS_MAJOR, KITCHEN_BEACON_MINOR);
    private static final Region TECH_BEACON_REGION = new Region("tech", null, TMW_BEACONS_MAJOR, TECH_BEACON_MINOR);
    private static final String REQUEST_STRING_MINOR = "minor";
    private static final NameValuePair REQUEST_PARAMS_CREATIVE_MINOR = new BasicNameValuePair(REQUEST_STRING_MINOR, Integer.toString(CREATIVE_BEACON_MINOR));
    private static final NameValuePair REQUEST_PARAMS_KITCHEN_MINOR = new BasicNameValuePair(REQUEST_STRING_MINOR, Integer.toString(KITCHEN_BEACON_MINOR));
    private static final NameValuePair REQUEST_PARAMS_TECH_MINOR = new BasicNameValuePair(REQUEST_STRING_MINOR, Integer.toString(TECH_BEACON_MINOR));
    private static final int DATA_UPDATED_MESSAGE_CODE = 0;
    
    private LiveCard _beaconsLiveCard;
    private RemoteViews _beaconLocationView;
    private RemoteViews _discoveringView;

    private Region.State _creativeRegionState = Region.State.OUTSIDE;
    private Region.State _kitchenRegionState = Region.State.OUTSIDE;
    private Region.State _techRegionState = Region.State.OUTSIDE;

    private BeaconManager _beaconManager;
    private List<Beacon> _beacons = null;
    private final IBinder _binder = new GlassAppBinder();

    private DataUpdateTask _dataUpdateTask;

    private ArrayList<NameValuePair> _requestParamsList = new ArrayList<NameValuePair>();


    public class GlassAppBinder extends Binder
    {
        public GlasstimoteService getService() {
            return GlasstimoteService.this;
        }
    }

    public GlasstimoteService(){}

    @Override
    public void onCreate() {
        super.onCreate();
        _beaconManager = new BeaconManager(this);
        _beaconManager.setRangingListener(_rangingListener);

        // _dataUpdateIntent = new Intent(this, DataUpdateActivity.class);
        // _dataUpdateIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {

        createLiveCard();
        _beaconsLiveCard.setViews(_discoveringView);

        checkBTStatusAndStartRanging();
        return START_STICKY;
    }

    private void checkBTStatusAndStartRanging() {

        // Check if device supports Bluetooth Low Energy.
        if (!_beaconManager.hasBluetooth()) {

            Toast.makeText(this, getString(R.string.error_bluetooth_le_unsupported), Toast.LENGTH_LONG).show();

        } else if (!_beaconManager.isBluetoothEnabled()) {

            // If Bluetooth is not enabled, let user enable it.
            Toast.makeText(this, getString(R.string.error_bluetooth_not_enabled), Toast.LENGTH_LONG).show();

        } else {

            connectToService();
        }
    }

    private void connectToService() {
        _beaconManager.connect(new BeaconManager.ServiceReadyCallback() {
            @Override
            public void onServiceReady() {
                try {
                    _beaconManager.startRanging(CREATIVE_BEACON_REGION);
                    _beaconManager.startRanging(KITCHEN_BEACON_REGION);
                    _beaconManager.startRanging(TECH_BEACON_REGION);
                } catch (RemoteException e) {
                    e.printStackTrace();
                }
            }
        });
    }

    @Override
    public void onDestroy() {
        _beaconManager.disconnect();
        unpublishLiveCard();
        super.onDestroy();
    }

    private BeaconManager.RangingListener _rangingListener = new BeaconManager.RangingListener() {
        @Override
        public void onBeaconsDiscovered(Region region, List<Beacon> beacons) {

            _beacons = beacons;

            if (beacons.size() > 0) {

                checkBeaconPositions();
            }
        }
    };

    private void checkBeaconPositions() {

        // loop through beacons.
        for (Beacon beacon : _beacons) {

            // ensure these are the TMW estimotes.
            if (beacon.getMajor() != TMW_BEACONS_MAJOR) continue;

            // get the distance between the beacon and the device.
            double beaconDistance = Utils.computeAccuracy(beacon);
            int beaconMinor = beacon.getMinor();


            if (beaconDistance < BEACON_REGION_ENTRY_DISTANCE) {

                // if we are close to a specific beacon, and not already in
                // a state inside the beacon's region range...
                if (beaconMinor == CREATIVE_BEACON_MINOR && _creativeRegionState == Region.State.OUTSIDE) {

                    Log.i(TAG, "entering creative... distance: " + beaconDistance);

                    // stop looking for the other beacons, as we are in range of this one.
                    try {
                        _beaconManager.stopRanging(KITCHEN_BEACON_REGION);
                        _beaconManager.stopRanging(TECH_BEACON_REGION);
                    } catch (RemoteException e) {
                        e.printStackTrace();
                    }

                    // switch to a state inside the range of this beacon.
                    _creativeRegionState = Region.State.INSIDE;

                    _requestParamsList.clear();
                    _requestParamsList.add(REQUEST_PARAMS_CREATIVE_MINOR);

                    loadLocationData();

                } else if (beaconMinor == KITCHEN_BEACON_MINOR && _kitchenRegionState == Region.State.OUTSIDE) {

                    Log.i(TAG, "entering kitchen... distance: " + beaconDistance);

                    // stop looking for the other beacons, as we are in range of this one.
                    try {
                        _beaconManager.stopRanging(CREATIVE_BEACON_REGION);
                        _beaconManager.stopRanging(TECH_BEACON_REGION);
                    } catch (RemoteException e) {
                        e.printStackTrace();
                    }

                    // switch to a state inside the range of this beacon.
                    _kitchenRegionState = Region.State.INSIDE;

                    _requestParamsList.clear();
                    _requestParamsList.add(REQUEST_PARAMS_KITCHEN_MINOR);

                    loadLocationData();

                } else if (beaconMinor == TECH_BEACON_MINOR && _techRegionState == Region.State.OUTSIDE) {

                    Log.i(TAG, "entering tech... distance: " + beaconDistance);

                    // stop looking for the other beacons, as we are in range of this one.
                    try {
                        _beaconManager.stopRanging(CREATIVE_BEACON_REGION);
                        _beaconManager.stopRanging(KITCHEN_BEACON_REGION);
                    } catch (RemoteException e) {
                        e.printStackTrace();
                    }

                    // switch to a state inside the range of this beacon.
                    _techRegionState = Region.State.INSIDE;

                    _requestParamsList.clear();
                    _requestParamsList.add(REQUEST_PARAMS_TECH_MINOR);

                    loadLocationData();
                }
            } else if (beaconDistance >= BEACON_REGION_EXIT_DISTANCE) {

                // if we are far enough away from a specific beacon region,
                // and not already in a state outside the beacon's region range...
                if (beaconMinor == CREATIVE_BEACON_MINOR && _creativeRegionState == Region.State.INSIDE) {

                    Log.i(TAG, "exiting creative... distance: " + beaconDistance);

                    // switch to a state outside the range of this beacon.
                    _creativeRegionState = Region.State.OUTSIDE;
                    _beaconsLiveCard.setViews(_discoveringView);

                    // restart looking for the other beacons, as we are no longer in range of this one.
                    try {
                        _beaconManager.startRanging(KITCHEN_BEACON_REGION);
                        _beaconManager.startRanging(TECH_BEACON_REGION);
                    } catch (RemoteException e) {
                        e.printStackTrace();
                    }

                } else if (beaconMinor == KITCHEN_BEACON_MINOR && _kitchenRegionState == Region.State.INSIDE) {

                    Log.i(TAG, "exiting kitchen... distance: " + beaconDistance);

                    // switch to a state outside the range of this beacon.
                    _kitchenRegionState = Region.State.OUTSIDE;
                    _beaconsLiveCard.setViews(_discoveringView);

                    // restart looking for the other beacons, as we are no longer in range of this one.
                    try {
                        _beaconManager.startRanging(CREATIVE_BEACON_REGION);
                        _beaconManager.startRanging(TECH_BEACON_REGION);
                    } catch (RemoteException e) {
                        e.printStackTrace();
                    }

                } else if (beaconMinor == TECH_BEACON_MINOR && _techRegionState == Region.State.INSIDE) {

                    Log.i(TAG, "exiting tech... distance: " + beaconDistance);

                    // switch to a state outside the range of this beacon.
                    _techRegionState = Region.State.OUTSIDE;
                    _beaconsLiveCard.setViews(_discoveringView);

                    // restart looking for the other beacons, as we are no longer in range of this one.
                    try {
                        _beaconManager.startRanging(CREATIVE_BEACON_REGION);
                        _beaconManager.startRanging(KITCHEN_BEACON_REGION);
                    } catch (RemoteException e) {
                        e.printStackTrace();
                    }
                }
            }
        }
    }

    private void loadLocationData ()
    {
        Log.i(TAG, "GlasstimoteService:: [locaLocationData] request params value: " + _requestParamsList.get(0).getValue());

        //startActivity(_dataUpdateIntent);

        // cancel old update task if it exists
        if (_dataUpdateTask != null)
        {
            Log.i(TAG, "GlasstimoteService:: [locaLocationData] cancelling task.");
            _dataUpdateTask.cancel(true);
        }

        Message dataUpdatedMessage = _messageQueueHandler.obtainMessage(DATA_UPDATED_MESSAGE_CODE);

        _dataUpdateTask = new DataUpdateTask();
        _dataUpdateTask.run(_requestParamsList, dataUpdatedMessage);
    }

    private void createLiveCard ()
    {
        if (_beaconsLiveCard == null) {
            // create a new live card.
            _beaconsLiveCard = new LiveCard(this, LIVE_CARD_TAG);

            Intent menuIntent = new Intent(this, LiveCardMenuActivity.class);
            menuIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            _beaconsLiveCard.setAction(PendingIntent.getActivity(this, 0, menuIntent, 0));

            // publish card.
            _beaconsLiveCard.publish(LiveCard.PublishMode.REVEAL);

            // create views.
            _discoveringView = new RemoteViews(getPackageName(), R.layout.discovering_card);
            _beaconLocationView = new RemoteViews(getPackageName(), R.layout.beacon_location_card);
        }
    }

    public void showLiveCard (Bitmap locationImage, String locationTitle, String locationInfo)
    {
        if (_beaconsLiveCard != null)
        {
            _beaconLocationView.setImageViewBitmap(R.id.location_icon, locationImage);
            _beaconLocationView.setTextViewText(R.id.location_name, locationTitle);
            _beaconLocationView.setTextViewText(R.id.location_info, locationInfo);
            _beaconsLiveCard.setViews(_beaconLocationView);
        }
    }

    private void unpublishLiveCard ()
    {
        if (_beaconsLiveCard != null && _beaconsLiveCard.isPublished())
        {
            _beaconsLiveCard.unpublish();
            _beaconsLiveCard = null;
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return _binder;
    }


    private Handler _messageQueueHandler = new Handler()
    {
        @Override
        public void handleMessage (Message message)
        {
            switch (message.what)
            {
                case DATA_UPDATED_MESSAGE_CODE:

                    Log.i(TAG, "GlasstimoteService:: [_messageQueueHandler] updated data message received");

                    LocationDataVO vo = (LocationDataVO) message.obj;

                    showLiveCard(vo.locationImage, vo.locationName, vo.locationInfo);

                    break;
            }

            super.handleMessage(message);
        }
    };
}
