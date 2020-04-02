package edu.uw.covidsafe.comms;
import android.content.Context;
import android.content.SharedPreferences;
import android.location.Location;
import android.os.Messenger;
import android.util.Log;

import com.example.covidsafe.R;
import com.google.gson.JsonObject;

import org.json.JSONObject;

import edu.uw.covidsafe.Area;
import edu.uw.covidsafe.ble.BleDbRecordRepository;
import edu.uw.covidsafe.ble.BleRecord;
import edu.uw.covidsafe.gps.GpsDbRecordRepository;
import edu.uw.covidsafe.gps.GpsRecord;
import edu.uw.covidsafe.seed_uuid.SeedUUIDDbRecordRepository;
import edu.uw.covidsafe.seed_uuid.SeedUUIDRecord;
import edu.uw.covidsafe.utils.Constants;
import edu.uw.covidsafe.utils.CryptoUtils;
import edu.uw.covidsafe.utils.Utils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

public class PullFromServerTask implements Runnable {

    Messenger messenger;
    Context context;
    HashMap<String,Long> scannedBleMap;

    public PullFromServerTask(Messenger messenger, Context context) {
        this.messenger = messenger;
        this.context = context;
        this.scannedBleMap = new HashMap<>();
    }

    @Override
    public void run() {
        Log.e("uuid", "PULL FROM SERVER");

        SharedPreferences prefs = context.getSharedPreferences(Constants.SHARED_PREFENCE_NAME, Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();

        //get most recent GPS entry as coarse location and send it
        GpsDbRecordRepository gpsRepo = new GpsDbRecordRepository(context);
        List<GpsRecord> gpsRecords = gpsRepo.getSortedRecords();
        if (gpsRecords.size() == 0) {
            return;
        }
        GpsRecord gpsRecord = gpsRecords.get(0);

        int currentPrecision = Constants.DefaultGpsCoarsenessInDecimalPoints;
        int maxPayloadSize = 0;

        long possibleLastQueryTime = prefs.getLong(context.getString(R.string.time_of_last_query_pkey), 0L);
        long lastQueryTime = 0;
        if (possibleLastQueryTime != 0) {
            lastQueryTime = possibleLastQueryTime;
        }

        while (currentPrecision < Constants.MaximumGpsPrecisionAllowed) {
            double preciseLat = Utils.getCoarseGpsCoord(gpsRecord.getLat(), currentPrecision);
            double preciseLong = Utils.getCoarseGpsCoord(gpsRecord.getLongi(), currentPrecision);

            int sizeOfPayload = howBig(preciseLat,preciseLong,lastQueryTime);
            if (sizeOfPayload > maxPayloadSize) {
                currentPrecision += 1;
            }
            else {
                break;
            }
        }

        double preciseLat = Utils.getCoarseGpsCoord(gpsRecord.getLat(), currentPrecision);
        double preciseLong = Utils.getCoarseGpsCoord(gpsRecord.getLongi(), currentPrecision);

        List<SeedUUIDRecord> seedUUIDRecords = getMessages(preciseLat,preciseLong,lastQueryTime);

        // TODO: set intersection between ble IDs and received IDs, don't do a linear for loop
        // check that the set intersection will work.
        BleDbRecordRepository repo = new BleDbRecordRepository(context);
        List<BleRecord> bleRecords = repo.getAllRecords();
        for (BleRecord bleRecord : bleRecords) {
            Log.e("ble",bleRecord.toString());
            scannedBleMap.put(bleRecord.getUuid(), bleRecord.getTs());
        }

        for (SeedUUIDRecord seedUUIDRecord : seedUUIDRecords) {
            if (isExposed(seedUUIDRecord.seed, seedUUIDRecord.ts)) {
                notifyUserOfExposure();
                break;
            }
        }

        List<String> announcements = announce(preciseLat,preciseLong,lastQueryTime);
        if (announcements.size() > 0) {
            makeAnnouncement(announcements);
        }

        editor.putLong(context.getString(R.string.time_of_last_query_pkey), System.currentTimeMillis());
        editor.commit();
    }

    // sync blockig op
    public int howBig(double lat, double longi, long ts) {
        JsonObject obj = new JsonObject();
        obj.addProperty("lat",lat);
        obj.addProperty("longi",longi);
        obj.addProperty("ts",ts);

        int bigness = 0;

        return bigness;
    }

    public List<SeedUUIDRecord> getMessages(double lat, double longi, long ts) {
        // return list of seeds and timestamps
        // check if the areas returned in these messages match our GPS timestamps
        JsonObject obj = new JsonObject();
        obj.addProperty("lat",lat);
        obj.addProperty("longi",longi);
        obj.addProperty("ts",ts);

        //send request

        /////////////////////////////////////////////////////////////////////////
        //get response
        List<Area> areas = new ArrayList<Area>();
        List<SeedUUIDRecord> receivedRecords = new ArrayList<SeedUUIDRecord>();
        /////////////////////////////////////////////////////////////////////////

        List<SeedUUIDRecord> filteredRecords = new ArrayList<SeedUUIDRecord>();

        int counter = 0;
        for (Area area : areas) {
            GpsDbRecordRepository gpsRepo = new GpsDbRecordRepository(context);
            List<GpsRecord> gpsRecords = gpsRepo.getRecordsBetweenTimestamps(area.tstart, area.tend);

            for (GpsRecord record : gpsRecords) {
                float[] result = new float[3];
                Location.distanceBetween(record.getLat(), record.getLongi(), area.latitude, area.longitude, result);

                if ((result.length == 1 && result[0] < area.radius) ||
                    (result.length == 2 && result[1] < area.radius) ||
                    (result.length >= 3 && result[2] < area.radius)) {
                    filteredRecords.add(receivedRecords.get(counter));
                }
            }
            counter += 1;
        }

        return filteredRecords;
    }

    public List<String> announce(double lat, double longi, long ts) {
        JsonObject obj = new JsonObject();
        obj.addProperty("lat",lat);
        obj.addProperty("longi",longi);
        obj.addProperty("ts",ts);

        ///////////////////////////////////////////////////
        List<Area> areas = new ArrayList<Area>();
        List<String> messages = new ArrayList<String>();
        ///////////////////////////////////////////////////

        int counter = 0;
        List<String> filteredMessages = new ArrayList<String>();
        for (Area area : areas) {
            GpsDbRecordRepository gpsRepo = new GpsDbRecordRepository(context);
            List<GpsRecord> gpsRecords = gpsRepo.getRecordsBetweenTimestamps(area.tstart, area.tend);

            for (GpsRecord record : gpsRecords) {
                float[] result = new float[3];
                Location.distanceBetween(record.getLat(), record.getLongi(), area.latitude, area.longitude, result);

                if ((result.length == 1 && result[0] < area.radius) ||
                    (result.length == 2 && result[1] < area.radius) ||
                    (result.length >= 3 && result[2] < area.radius)) {
                    filteredMessages.add(messages.get(counter));
                }
            }
            counter += 1;
        }
        return filteredMessages;
    }

    // we get a seed and timestamp from the server for each infected person
    // check if we intersect with the infected person
    public boolean isExposed(String seed, long ts) {
        // if timestamp is in the future, something is wrong, return
        if (ts > System.currentTimeMillis()) {
            return false;
        }
        // convert our BLE DB records into convenient data structures
        // place the UUIDs into a map for easy lookup and checking

        // determine how many UUIDs to generate from the seed
        // based on time when the seed was generated and now.
        int diffBetweenNowAndTsInMinutes = (int)((System.currentTimeMillis() - ts)/1000/60);
        int numSeedsToGenerate = diffBetweenNowAndTsInMinutes / Constants.UUIDGenerationIntervalInMinutes;

        // if we need to generate too many timestamps, something is wrong, return.
        int infectionWindowInMinutes = (Constants.InfectionWindowInDays*24*60);
        int maxSeedsToGenerate = infectionWindowInMinutes / Constants.UUIDGenerationIntervalInMinutes;
        if (numSeedsToGenerate > maxSeedsToGenerate) {
            return false;
        }
        // generate all the seeds
        List<String> receivedUUIDs = CryptoUtils.chainGenerateUUIDFromSeed(seed, numSeedsToGenerate);

        // iterate through received IDs and see if they are in list of IDs which we have scanned
        // if so, check if those IDs had timestamps within some interval...
        // this interval has to be BluetoothScanIntervalInMinutes
        // user A can broadcast an ID at timestamp t
        // user B may only wake up to scan the ID after BluetoothScanIntervalInMinutes
        List<Long> matches = new ArrayList<>();
        int bluetoothScanIntervalInMilliseconds = Constants.BluetoothScanIntervalInMinutes*60000;
        int uuidGenerationIntervalInMillliseconds = Constants.UUIDGenerationIntervalInMinutes*60000;

        for (String receivedUUID : receivedUUIDs) {
            if (scannedBleMap.keySet().contains(receivedUUID)) {
                if (Math.abs(scannedBleMap.get(receivedUUID) - ts) < bluetoothScanIntervalInMilliseconds) {
                    matches.add(ts);
                }
            }
            ts += uuidGenerationIntervalInMillliseconds;
        }

        // calculate how many matches we need to say user is exposed for at least 10 minutes
        // return false if there simply not enough matches to determine this.
        int numConsecutiveMatchesNeeded = Constants.CDCExposureTimeInMinutes/Constants.BluetoothScanIntervalInMinutes;
        if (matches.size() < numConsecutiveMatchesNeeded) {
            return false;
        }

        // take diff of timestamps when we had a UUID match
        List<Integer> diff = new ArrayList<Integer>();
        for (int i = 0; i < matches.size()-1; i++) {
            diff.add((int)(matches.get(i+1)-matches.get(i)));
        }

        // counter tracks how many occurrences of
        // uuidGenerationIntervalInMillliseconds we have in a row
        // maxcounter is the 'max streak'
        int streak = 0;
        int maxStreak = 0;

        //check that we have at least numConsecutiveMatchesNeeded
        //matches of uuidGenerationIntervalInMillliseconds
        for (int i = 0; i < diff.size(); i++) {
            if (Math.abs(diff.get(i)-bluetoothScanIntervalInMilliseconds)
                < Constants.TimestampDeviationInMilliseconds) {
                streak += 1;
                if (streak > maxStreak) {
                    maxStreak = streak;
                }
            }
            else {
                streak = 0;
            }
        }

        if (maxStreak >= (numConsecutiveMatchesNeeded-1)) {
            return true;
        }
        return false;
    }

    public void notifyUserOfExposure() {
//        King County COVID-19 call center: 206-477-3977. Open daily from 8 a.m. to 7 p.m
//        Washington State COVID-19 call center: 800-525-0127
//        https://scanpublichealth.org/faq
    }

    public void makeAnnouncement(List<String> announcements) {
        // PSA
    }
}
