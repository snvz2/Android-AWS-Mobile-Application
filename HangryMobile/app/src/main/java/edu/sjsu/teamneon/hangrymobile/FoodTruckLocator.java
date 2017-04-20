package edu.sjsu.teamneon.hangrymobile;

import android.Manifest;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.support.v4.app.FragmentActivity;
import android.view.View;
import android.widget.Button;
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.amazonaws.auth.CognitoCachingCredentialsProvider;
import com.amazonaws.mobileconnectors.dynamodbv2.dynamodbmapper.DynamoDBMapper;
import com.amazonaws.mobileconnectors.dynamodbv2.dynamodbmapper.DynamoDBScanExpression;
import com.amazonaws.mobileconnectors.dynamodbv2.dynamodbmapper.PaginatedScanList;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClient;
import com.google.android.gms.maps.CameraUpdate;
import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.SupportMapFragment;
import com.google.android.gms.maps.model.BitmapDescriptorFactory;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.Marker;
import com.google.android.gms.maps.model.MarkerOptions;

import java.util.ArrayList;

public class FoodTruckLocator extends FragmentActivity implements OnMapReadyCallback, LocationListener {
    Button btnLoc;
    GPSTracker gps;
    private static final int PERMS_REQUEST_CODE = 123;
    private GoogleMap mMap;
    private ArrayList<Marker> truckList = new ArrayList<>();
    private RelativeLayout frameTruckList;
    private Marker locationMarker;
    private float defaultZoom;
    private static String currentLocation = null;

    @Override
    public void onLocationChanged(Location location) {
        LatLng latLng = new LatLng(location.getLatitude(), location.getLongitude());
        if (locationMarker != null) {
            locationMarker.remove();
        }
        locationMarker = mMap.addMarker(
                new MarkerOptions().position(latLng).title(
                        currentLocation).icon(BitmapDescriptorFactory.defaultMarker(
                                BitmapDescriptorFactory.HUE_AZURE)));
        CameraUpdate cameraUpdate = CameraUpdateFactory.newLatLngZoom(latLng, 10);
        mMap.animateCamera(cameraUpdate);
        mMap.moveCamera(CameraUpdateFactory.newLatLng(latLng));
        mMap.animateCamera(CameraUpdateFactory.zoomTo(defaultZoom));
        gps.locationManager.removeUpdates(this);
    }

    @Override
    public void onStatusChanged(String s, int i, Bundle bundle) {

    }

    @Override
    public void onProviderEnabled(String s) {

    }

    @Override
    public void onProviderDisabled(String s) {

    }

    public void updatePos() {
        gps = new GPSTracker(FoodTruckLocator.this);

        requestPerms();
        if(gps.canGetLocation() && hasPermissions()) {
            onLocationChanged(gps.getLocation());
            gps.getLocation();
            double latitude = gps.getLatitude();
            double longitude = gps.getLongitude();
            /* If we have prior markers, free them now */
            if (!truckList.isEmpty()) {
                truckList.clear();
            }
            /* Get food truck locations */
            new scanAllTrucks().execute();
            /* Add Markers for newly discovered food trucks */


            Toast.makeText(
                    getApplicationContext(),
                    "Your Location is -\nLat: " + latitude + "\nLong: "
                            + longitude, Toast.LENGTH_LONG).show();

        } else {

            gps.showSettingsAlert();
            requestPerms();
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        /* Set resource values */
        defaultZoom = Float.parseFloat(getResources().getString(R.string.default_zoom));
        currentLocation = getResources().getString(R.string.current_location);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_food_truck_locator);

        // Obtain the SupportMapFragment and get notified when the map is ready to be used.
        SupportMapFragment mapFragment = (SupportMapFragment) getSupportFragmentManager()
                .findFragmentById(R.id.truckMap);
        mapFragment.getMapAsync(this);
        /* Get the truck list frame layout handle */
        frameTruckList = (RelativeLayout) findViewById(R.id.truckList);
        btnLoc = (Button) findViewById(R.id.gps);

        btnLoc.setOnClickListener(new View.OnClickListener() {

            @Override
            public void onClick(View v) {
                updatePos();
            }
        });
    }


/*
    private void CameraUpdate(){

    }*/


    /**
     * Manipulates the map once available.
     * This callback is triggered when the map is ready to be used.
     * This is where we can add markers or lines, add listeners or move the camera. In this case,
     * we just add a marker near Sydney, Australia.
     * If Google Play services is not installed on the device, the user will be prompted to install
     * it inside the SupportMapFragment. This method will only be triggered once the user has
     * installed Google Play services and returned to the app.
     */
    @Override
    public void onMapReady(GoogleMap googleMap) {
        mMap = googleMap;

        /* Add a marker for test food truck and move camera */
        //mMap.addMarker(new MarkerOptions().position(testFoodTruck.getLatLng()).title(testFoodTruck.getName()));
        updatePos();
    }
    /**
     * Checks if Permissions are valid
     */
    private boolean hasPermissions(){
        int res = 0;
        String[] permissions = new String[]{Manifest.permission.ACCESS_FINE_LOCATION};
        for (String perms : permissions){
            res = checkCallingOrSelfPermission(perms);
            if (!(res == PackageManager.PERMISSION_GRANTED)){
                return false;
            }
        }
        return true;
    }

    /**
     * Displays dialog for user to enable locations permissions (ACCESS_FINE_LOCATION)
     */
    private void requestPerms(){
        String[] permissions = new String[]{Manifest.permission.ACCESS_FINE_LOCATION};
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M){
            requestPermissions(permissions,PERMS_REQUEST_CODE);}

    }

    /**
     * Amazon AWS stuff goes down here
     */
    private class storeTruck extends AsyncTask<String, Integer, Integer>{
        private String truckName;
        String truckLon;
        String truckLat;
        public storeTruck(String name, String lon, String lat)
        {
            truckName = name;
            truckLon = lon;
            truckLat = lat;
        }
        @Override
        protected Integer doInBackground(String... params){

            //Instantiate manager class (Currently only has Dynamo) and get credentials for mapper
            ManagerClass managerClass = new ManagerClass();
            CognitoCachingCredentialsProvider credentialsProvider =
                    managerClass.getCredentials(FoodTruckLocator.this); //Pass in the activity name
            AmazonDynamoDBClient ddbClient = new AmazonDynamoDBClient(credentialsProvider);
            DynamoDBMapper mapper = new DynamoDBMapper(ddbClient);

            //Get values through ID of field or Geo code
            FoodTruck newTruck = new FoodTruck();
            newTruck.setLat(truckLat);
            newTruck.setLon(truckLon);
            newTruck.setName(truckName);
            mapper.save(newTruck);

            return null;
        }
    }

    private class updateTruck extends AsyncTask<Void, Integer, Integer>{
        @Override
        protected Integer doInBackground(Void... params){

            //Instantiate manager class (Currently only has Dynamo) and get credentials for mapper
            ManagerClass managerClass = new ManagerClass();
            CognitoCachingCredentialsProvider credentialsProvider =
                    managerClass.getCredentials(FoodTruckLocator.this); //Pass in the activity name
            AmazonDynamoDBClient ddbClient = new AmazonDynamoDBClient(credentialsProvider);
            DynamoDBMapper mapper = new DynamoDBMapper(ddbClient);

            //Selects a truck based on primary key (id) to update
            FoodTruck truckToUpdate = mapper.load(FoodTruck.class, 3);
            truckToUpdate.setName("New truck name");

            mapper.save(truckToUpdate);

            return null;
        }
    }

    private class deleteTruck extends AsyncTask<Void, Integer, Integer> {
        @Override
        protected Integer doInBackground(Void... params){

            //Instantiate manager class (Currently only has Dynamo) and get credentials for mapper
            ManagerClass managerClass = new ManagerClass();
            CognitoCachingCredentialsProvider credentialsProvider =
                    managerClass.getCredentials(FoodTruckLocator.this); //Pass in the activity name
            AmazonDynamoDBClient ddbClient = new AmazonDynamoDBClient(credentialsProvider);
            DynamoDBMapper mapper = new DynamoDBMapper(ddbClient);

            //Selects a truck based on primary key (id) to delete
            FoodTruck truckToDelete = mapper.load(FoodTruck.class, 6);

            mapper.delete(truckToDelete);

            return null;
        }
    }

    //Returns all the entry in the database
    private class scanAllTrucks extends AsyncTask<String, Void, Void>{
        private PaginatedScanList<FoodTruck> result;
        @Override
        protected Void doInBackground(String... params) {

            //Instantiate manager class (Currently only has Dynamo) and get credentials for mapper
            ManagerClass managerClass = new ManagerClass();
            CognitoCachingCredentialsProvider credentialsProvider =
                    managerClass.getCredentials(FoodTruckLocator.this); //Pass in the activity name
            AmazonDynamoDBClient ddbClient = new AmazonDynamoDBClient(credentialsProvider);
            DynamoDBMapper mapper = new DynamoDBMapper(ddbClient);

            DynamoDBScanExpression scanExpression = new DynamoDBScanExpression();
            result = mapper.scan(FoodTruck.class, scanExpression);

            return null;
        }

        @Override
        protected void onPostExecute(Void v) {
            /* Remove all truck views */
            frameTruckList.removeAllViews();
            TextView previous = null;
            int id = 1;

            /* Iterate through all newly discovered trucks */
            for (FoodTruck truck: result) {
                LatLng latLng = new LatLng(
                        Double.parseDouble(truck.getLat()),
                        Double.parseDouble(truck.getLon()));
                /* Add truck markers to map */
                truckList.add(mMap.addMarker(
                        new MarkerOptions().position(latLng).title(
                                truck.getName())));
                /* Create a truck view and add it to LinearLayout */
                TextView truckView = new TextView(FoodTruckLocator.this);
                truckView.setTextSize(30);
                truckView.setId(id++);
                /* Create parameters for the new truck view */
                RelativeLayout.LayoutParams params =
                        new RelativeLayout.LayoutParams(
                                RelativeLayout.LayoutParams.WRAP_CONTENT,
                                RelativeLayout.LayoutParams.WRAP_CONTENT);

                /* If there was a prior truck view, this one goes below it */
                if (previous != null) {
                    params.addRule(RelativeLayout.BELOW, previous.getId());
                }
                truckView.setLayoutParams(params);
                truckView.setText(truck.getName());
                frameTruckList.addView(truckView);
                previous = truckView;
            }
        }
    }
}
