/* Copyright 2011-2013 Google Inc.
 * Copyright 2013 mike wakerly <opensource@hoho.com>
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301,
 * USA.
 *
 * Project home page: https://github.com/mik3y/usb-serial-for-android
 */

package com.hehelabs.wegootestthree;

import android.content.Context;
import android.content.Intent;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.location.Location;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.provider.Settings;
import android.support.v7.app.ActionBarActivity;
import android.support.v7.widget.Toolbar;
import android.util.Log;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.gms.appindexing.AppIndex;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.location.LocationServices;
import com.jjoe64.graphview.DefaultLabelFormatter;
import com.jjoe64.graphview.GraphView;
import com.jjoe64.graphview.Viewport;
import com.jjoe64.graphview.series.DataPoint;
import com.jjoe64.graphview.series.LineGraphSeries;
import com.squareup.okhttp.MediaType;
import com.squareup.okhttp.OkHttpClient;
import com.squareup.okhttp.Request;
import com.squareup.okhttp.RequestBody;
import com.squareup.okhttp.Response;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.text.DateFormat;
import java.text.NumberFormat;
import java.util.Date;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import driver.UsbSerialPort;
import util.HexDump;
import util.SerialInputOutputManager;


/**
 * Monitors a single {@link UsbSerialPort} instance, showing all data
 * received.
 *
 * @author mike wakerly (opensource@hoho.com)
 */
public class SerialConsoleActivity extends ActionBarActivity implements
        GoogleApiClient.ConnectionCallbacks, GoogleApiClient.OnConnectionFailedListener {

    private final String TAG = SerialConsoleActivity.class.getSimpleName();

    private Toolbar toolbar;

    /**
     * Driver instance, passed in statically via
     * {@link #show(Context, UsbSerialPort, String, String)}.
     * <p/>
     * <p/>
     * This is a devious hack; it'd be cleaner to re-create the driver using
     * arguments passed in with the {@link #startActivity(Intent)} intent. We
     * can get away with it because both activities will run in the same
     * process, and this is a simple demo.
     */


    private static UsbSerialPort sPort = null;
    private static String mName;
    private static String mId;

    private TextView oxygenValue;
    private TextView pulseValue;
    private TextView tempValue;

    private String temperature;
    private String pulse;
    private String oxygen;
    private String EcgValues;
    private int lastX = 0;
    private String lat="lat";
    private String lon="lon";

    private String deviceID;
    private String mLastTime;


    //ECG graph initializations
    private GraphView graph;
    private final Handler mHandler = new Handler();
    private Runnable mTimer1;
    private LineGraphSeries<DataPoint> mSeries1;

    private GoogleApiClient mGoogleApiClient;
    private Location mLastLocation;
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private String convertToString;

    private final ExecutorService mExecutor = Executors.newSingleThreadExecutor();

    private SerialInputOutputManager mSerialIoManager;

    //data JSON Object
    private JSONObject JSONparams;
    OkHttpClient client = new OkHttpClient();

    private final SerialInputOutputManager.Listener mListener =
            new SerialInputOutputManager.Listener() {

                @Override
                public void onRunError(Exception e) {
                    Log.d(TAG, "Runner stopped.");
                }

                @Override
                public void onNewData(final byte[] data) {
                    SerialConsoleActivity.this.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            SerialConsoleActivity.this.updateReceivedData(data);
                        }
                    });
                }
            };

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.serial_console);

        //Keep the screen on for this activity
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        deviceID = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);

        toolbar = (Toolbar) findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);


        tempValue = (TextView) findViewById(R.id.tempvalue);
        oxygenValue = (TextView) findViewById(R.id.oxygenvalue);
        pulseValue = (TextView) findViewById(R.id.psvalue);

        graph = (GraphView) findViewById(R.id.graph);

        mSeries1 = new LineGraphSeries<DataPoint>();

        graph.addSeries(mSeries1);

        //customize a little bit the view port
        Viewport viewport = graph.getViewport();
        viewport.setYAxisBoundsManual(true);
        viewport.setMinY(0);
        viewport.setMaxY(5);
        viewport.setScrollable(true);


        graph.getGridLabelRenderer().setGridColor(R.color.activityBackground);
        graph.getGridLabelRenderer().setHighlightZeroLines(true);
        graph.getGridLabelRenderer().setHorizontalLabelsVisible(false);


        mGoogleApiClient = new GoogleApiClient.Builder(this).addApi(AppIndex.API).build();

        //initializing google location services
        buildGoogleApiClient();

    }

    private synchronized void buildGoogleApiClient() {
        mGoogleApiClient = new GoogleApiClient.Builder(this)
                .addConnectionCallbacks(this)
                .addOnConnectionFailedListener(this)
                .addApi(LocationServices.API)
                .build();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle item selection
        switch (item.getItemId()) {
            case R.id.settings:
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        mGoogleApiClient.connect();
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopIoManager();
        if (sPort != null) {
            try {
                sPort.close();
            } catch (IOException e) {
                // Ignore.
            }
            sPort = null;
        }
        finish();

        if (mGoogleApiClient.isConnected()) {
            mGoogleApiClient.disconnect();
        }

    }

    @Override
    protected void onStop() {
        super.onStop();
        if (mGoogleApiClient.isConnected()) {
            mGoogleApiClient.disconnect();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        Log.d(TAG, "Resumed, port=" + sPort);
        if (sPort == null) {
            //mTitleTextView.setText("No serial device.");
            Toast.makeText(getApplicationContext(), "No serial device.", Toast.LENGTH_LONG).show();
        } else {
            final UsbManager usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);

            UsbDeviceConnection connection = usbManager.openDevice(sPort.getDriver().getDevice());
            if (connection == null) {
                //mTitleTextView.setText("Opening device failed");
                Toast.makeText(getApplicationContext(), "Opening device failed", Toast.LENGTH_LONG).show();
                return;
            }

            try {
                sPort.open(connection);
                sPort.setParameters(115200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE);
            } catch (IOException e) {
                Log.e(TAG, "Error setting up device: " + e.getMessage(), e);
                //mTitleTextView.setText("Error opening device: " + e.getMessage());
                Toast.makeText(getApplicationContext(), "Error opening device: " + e.getMessage(), Toast.LENGTH_LONG).show();
                try {
                    sPort.close();
                } catch (IOException e2) {
                    // Ignore.
                }
                sPort = null;
                return;
            }
            //mTitleTextView.setText("Serial device: " + sPort.getClass().getSimpleName());
            Toast.makeText(getApplicationContext(), "Serial device: " + sPort.getClass().getSimpleName(), Toast.LENGTH_LONG).show();
        }
        onDeviceStateChange();

        mGoogleApiClient.connect();
    }

    private void stopIoManager() {
        if (mSerialIoManager != null) {
            Log.i(TAG, "Stopping io manager ..");
            mSerialIoManager.stop();
            mSerialIoManager = null;
        }
    }

    private void startIoManager() {
        if (sPort != null) {
            Log.i(TAG, "Starting io manager ..");
            mSerialIoManager = new SerialInputOutputManager(sPort, mListener);
            mExecutor.submit(mSerialIoManager);
        }
    }

    private void onDeviceStateChange() {
        stopIoManager();
        startIoManager();
    }

    private void updateReceivedData(byte[] data) {

        //get time
        int lastY = 0;
        String delimiter = ",";
        String[] temp;

        final String finalData = HexDump.dumpHexString(data);
        final String message = "Read " + data.length + " bytes: \n"
                + HexDump.dumpHexString(data) + "\n\n";
        pulseValue.setText("");
        oxygenValue.setText("");
        tempValue.setText("");
        //get stsem time
        mLastTime = DateFormat.getDateTimeInstance().format(new Date());

        temp = finalData.split(delimiter);

        pulse = temp[0];
        oxygen = temp[1];
        temperature = temp[2];
        EcgValues = temp[3];

        pulseValue.append(pulse + "bpm");
        oxygenValue.append(oxygen + "%");
        tempValue.append(temperature + "C");

        if (Integer.parseInt(oxygen) < 50 || Integer.parseInt(oxygen) == 100) {
            oxygenValue.setBackgroundResource(R.drawable.oval_red);
        } else {
            oxygenValue.setBackgroundResource(R.drawable.oval);
        }

        lastY = Integer.parseInt(EcgValues);


        //update the ECG chart wirth the sensor data
        mSeries1.appendData(new DataPoint(lastX++, lastY), true, 50);

        //sending data to the server
        if (pulse != "0" ||oxygen != "0" || temperature != "0" || EcgValues != "0" ){
            DataNetworking sendData = new DataNetworking();
            sendData.execute();
        }

    }

    /**
     * Starts the activity, using the supplied driver instance.
     *
     * @param driver
     * @param context
     * @param patientName
     * @param idNumber
     */
    static void show(Context context, UsbSerialPort port, String patientName, String idNumber) {
        sPort = port;
        mName = patientName;
        mId = idNumber;

        final Intent intent = new Intent(context, SerialConsoleActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_NO_HISTORY);
        context.startActivity(intent);
    }

    @Override
    public void onConnected(Bundle connectionHint) {

        mLastLocation = LocationServices.FusedLocationApi.getLastLocation(mGoogleApiClient);

        if (mLastLocation != null) {
            lat = String.valueOf(mLastLocation.getLatitude());
            lon = String.valueOf(mLastLocation.getLongitude());
            Log.d(TAG, "Latitude: " + lat);
            Log.d(TAG, "Longitude: " + lon);
        } else {
            Toast.makeText(this, "No Location detected", Toast.LENGTH_LONG).show();
        }

    }

    @Override
    public void onConnectionSuspended(int i) {

        Log.i(TAG, "Connection suspended");
        mGoogleApiClient.connect();

    }

    @Override
    public void onConnectionFailed(ConnectionResult result) {
        Log.i(TAG, "Connection failed: ConnectionResult.getErrorCode() = " + result.getErrorCode());
    }

    private class DataNetworking extends AsyncTask<Void, Void, String> {

        @Override
        protected String doInBackground(Void... params) {

            JSONparams = new JSONObject();

            try {
                JSONparams.put("name",mName);
                JSONparams.put("IDnumber",mId);
                JSONparams.put("pulse",pulse);
                JSONparams.put("oxygen",oxygen);
                JSONparams.put("temperature",temperature);
                JSONparams.put("ecgvalue",EcgValues);
                JSONparams.put("lat",lat);
                JSONparams.put("lon",lon);
                JSONparams.put("cTime", mLastTime);
                JSONparams.put("Device_uuid", deviceID);
            } catch (JSONException e) {
                Log.e(TAG,"Setting app JSON parameter"+e.getMessage(),e);
                e.printStackTrace();
            }

            RequestBody bodyData = RequestBody.create(JSON, String.valueOf(JSONparams));
            Request request = new Request.Builder()
                    .url("http://atago.kic.ac.jp:3000/mydata")
                    .post(bodyData)
                    .build();

            try {
                Response response = client.newCall(request).execute();
                return response.body().string();
            } catch (IOException e) {
                Log.e(TAG,"getting responce:"+e.getMessage(),e);
                e.printStackTrace();
            }

            return null;
        }

        @Override
        protected void onPostExecute(String s) {
            Log.d(TAG, "The returned responce"+s);
        }
    }
}

