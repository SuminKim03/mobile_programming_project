package org.techtown.weatherapp;

import android.Manifest;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Address;
import android.location.Geocoder;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.view.Menu;
import android.widget.EditText;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.view.GravityCompat;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationServices;
import com.squareup.picasso.Picasso;

import org.techtown.weatherapp.databinding.ActivityMainBinding;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

public class MainActivity extends AppCompatActivity {

    private ActivityMainBinding binding;
    private FusedLocationProviderClient fusedLocationClient;

    // 여기에 발급받은 API 키를 넣으세요
    private static final String API_KEY = "여기에 API key 입력";

    private static final int LOCATION_PERMISSION_REQUEST_CODE = 1001;
    private static final String PREFS_NAME = "weather_prefs";
    private static final String PREF_SAVED_LOCATIONS = "saved_locations";
    private static final int MENU_GROUP_STATIC = 1;
    private static final int MENU_GROUP_HEADER = 2;
    private static final int MENU_GROUP_SAVED = 3;
    private static final int MENU_CURRENT_LOCATION_ID = 100;
    private static final int MENU_ADD_LOCATION_ID = 101;
    private static final int MENU_SAVED_HEADER_ID = 200;
    private static final int MENU_EMPTY_SAVED_ID = 201;
    private static final int MENU_SAVED_LOCATION_BASE_ID = 1000;

    private final List<SavedLocation> savedLocations = new ArrayList<>();
    private final Map<Integer, SavedLocation> savedLocationMenuMap = new HashMap<>();
    private final ExecutorService geocodeExecutor = Executors.newSingleThreadExecutor();
    private boolean isUsingCurrentLocation = true;
    private SavedLocation activeSavedLocation = null;
    private double lastRequestedLatitude;
    private double lastRequestedLongitude;
    private boolean hasLastRequestedLocation = false;
    private String lastFallbackCityName = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);
        setupNavigationDrawer();
        loadSavedLocations();
        renderNavigationMenu();

        // 새로고침 버튼 클릭
        binding.refreshButton.setOnClickListener(v -> {
            if (isUsingCurrentLocation) {
                checkLocationPermissionAndFetch();
            } else if (activeSavedLocation != null) {
                loadWeatherForSavedLocation(activeSavedLocation);
            }
        });

        // 초기 실행 시 위치 권한 확인 및 날씨 가져오기
        checkLocationPermissionAndFetch();
    }

    private void setupNavigationDrawer() {
        binding.menuButton.setOnClickListener(v ->
                binding.drawerLayout.openDrawer(GravityCompat.START));

        binding.navigationView.setNavigationItemSelectedListener(item -> {
            handleNavigationSelection(item.getItemId());
            return true;
        });
    }

    private void renderNavigationMenu() {
        Menu menu = binding.navigationView.getMenu();
        menu.clear();

        menu.add(MENU_GROUP_STATIC, MENU_CURRENT_LOCATION_ID, Menu.NONE, "현재 위치 (GPS)");
        menu.add(MENU_GROUP_STATIC, MENU_ADD_LOCATION_ID, Menu.NONE, "주소 추가");
        menu.add(MENU_GROUP_HEADER, MENU_SAVED_HEADER_ID, Menu.NONE, "저장된 주소")
                .setEnabled(false);

        savedLocationMenuMap.clear();

        if (savedLocations.isEmpty()) {
            menu.add(MENU_GROUP_HEADER, MENU_EMPTY_SAVED_ID, Menu.NONE, "저장된 주소가 없습니다")
                    .setEnabled(false);
            return;
        }

        for (int i = 0; i < savedLocations.size(); i++) {
            int itemId = MENU_SAVED_LOCATION_BASE_ID + i;
            SavedLocation location = savedLocations.get(i);
            menu.add(MENU_GROUP_SAVED, itemId, Menu.NONE, location.label);
            savedLocationMenuMap.put(itemId, location);
        }
    }

    private void handleNavigationSelection(int itemId) {
        binding.drawerLayout.closeDrawer(GravityCompat.START);

        if (itemId == MENU_CURRENT_LOCATION_ID) {
            isUsingCurrentLocation = true;
            activeSavedLocation = null;
            checkLocationPermissionAndFetch();
        } else if (itemId == MENU_ADD_LOCATION_ID) {
            showAddLocationDialog();
        } else if (savedLocationMenuMap.containsKey(itemId)) {
            SavedLocation location = savedLocationMenuMap.get(itemId);
            if (location != null) {
                showSavedLocationActionDialog(location);
            }
        }
    }

    private void showAddLocationDialog() {
        final EditText input = new EditText(this);
        input.setHint("예: 서울특별시 중구");

        new AlertDialog.Builder(this)
                .setTitle("주소 추가")
                .setMessage("저장할 주소를 입력하세요")
                .setView(input)
                .setPositiveButton("저장", (dialog, which) -> {
                    String address = input.getText().toString().trim();
                    if (address.isEmpty()) {
                        Toast.makeText(this, "주소를 입력하세요", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    geocodeAndSaveAddress(address);
                })
                .setNegativeButton("취소", null)
                .show();
    }

    private void showSavedLocationActionDialog(SavedLocation location) {
        new AlertDialog.Builder(this)
                .setTitle(location.label)
                .setItems(new CharSequence[]{"이 위치 날씨 보기", "저장된 주소 삭제"},
                        (dialog, which) -> {
                            if (which == 0) {
                                isUsingCurrentLocation = false;
                                activeSavedLocation = location;
                                loadWeatherForSavedLocation(location);
                            } else if (which == 1) {
                                confirmDeleteLocation(location);
                            }
                        })
                .show();
    }

    private void confirmDeleteLocation(SavedLocation location) {
        new AlertDialog.Builder(this)
                .setTitle("주소 삭제")
                .setMessage("해당 주소를 삭제할까요?")
                .setPositiveButton("삭제", (dialog, which) -> deleteSavedLocation(location))
                .setNegativeButton("취소", null)
                .show();
    }

    private void deleteSavedLocation(SavedLocation target) {
        savedLocations.remove(target);
        persistSavedLocations();
        renderNavigationMenu();

        if (activeSavedLocation != null && activeSavedLocation.id.equals(target.id)) {
            activeSavedLocation = null;
            isUsingCurrentLocation = true;
            checkLocationPermissionAndFetch();
        }
        Toast.makeText(this, "저장된 주소를 삭제했습니다", Toast.LENGTH_SHORT).show();
    }

    private void geocodeAndSaveAddress(String addressInput) {
        showLoading(true);

        geocodeExecutor.execute(() -> {
            try {
                Geocoder geocoder = new Geocoder(MainActivity.this, Locale.getDefault());
                List<Address> results = geocoder.getFromLocationName(addressInput, 1);

                if (results == null || results.isEmpty()) {
                    runOnUiThread(() -> {
                        showLoading(false);
                        Toast.makeText(MainActivity.this, "주소를 찾을 수 없습니다", Toast.LENGTH_SHORT).show();
                    });
                    return;
                }

                Address address = results.get(0);
                SavedLocation newLocation = new SavedLocation(
                        UUID.randomUUID().toString(),
                        buildDisplayName(address, addressInput),
                        address.getLatitude(),
                        address.getLongitude(),
                        System.currentTimeMillis()
                );

                runOnUiThread(() -> {
                    savedLocations.add(0, newLocation);
                    persistSavedLocations();
                    renderNavigationMenu();
                    isUsingCurrentLocation = false;
                    activeSavedLocation = newLocation;
                    loadWeatherForSavedLocation(newLocation);
                });
            } catch (IOException e) {
                runOnUiThread(() -> {
                    showLoading(false);
                    Toast.makeText(MainActivity.this,
                            "주소 검색 실패: " + e.getMessage(),
                            Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    private String buildDisplayName(Address address, String fallback) {
        String line = address.getAddressLine(0);
        if (line == null || line.isEmpty()) {
            line = address.getSubLocality();
        }
        if (line == null || line.isEmpty()) {
            line = fallback;
        }
        return line;
    }

    private void loadWeatherForSavedLocation(SavedLocation location) {
        showLoading(true);
        fetchWeatherData(location.latitude, location.longitude);
    }

    private void persistSavedLocations() {
        SharedPreferences preferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        JSONArray array = new JSONArray();

        for (SavedLocation location : savedLocations) {
            JSONObject object = new JSONObject();
            try {
                object.put("id", location.id);
                object.put("label", location.label);
                object.put("latitude", location.latitude);
                object.put("longitude", location.longitude);
                object.put("savedAt", location.savedAt);
                array.put(object);
            } catch (JSONException ignored) {
            }
        }

        preferences.edit()
                .putString(PREF_SAVED_LOCATIONS, array.toString())
                .apply();
    }

    private void loadSavedLocations() {
        savedLocations.clear();
        SharedPreferences preferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String raw = preferences.getString(PREF_SAVED_LOCATIONS, null);

        if (raw == null || raw.isEmpty()) {
            return;
        }

        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                JSONObject object = array.getJSONObject(i);
                SavedLocation location = new SavedLocation(
                        object.optString("id", UUID.randomUUID().toString()),
                        object.optString("label", "저장된 위치"),
                        object.optDouble("latitude", 0),
                        object.optDouble("longitude", 0),
                        object.optLong("savedAt", System.currentTimeMillis())
                );
                savedLocations.add(location);
            }
            Collections.sort(savedLocations, (a, b) -> Long.compare(b.savedAt, a.savedAt));
        } catch (JSONException e) {
            Toast.makeText(this, "저장된 주소를 불러올 수 없습니다", Toast.LENGTH_SHORT).show();
        }
    }
    private void checkLocationPermissionAndFetch() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED) {
            // 권한이 이미 부여됨
            fetchLocation();
        } else {
            // 권한 요청
            ActivityCompat.requestPermissions(
                    this,
                    new String[]{
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION
                    },
                    LOCATION_PERMISSION_REQUEST_CODE
            );
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == LOCATION_PERMISSION_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // 권한 승인됨
                fetchLocation();
            } else {
                // 권한 거부됨
                Toast.makeText(this, "위치 권한이 필요합니다", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void fetchLocation() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            return;
        }

        showLoading(true);

        fusedLocationClient.getLastLocation()
                .addOnSuccessListener(this, location -> {
                    if (location != null) {
                        double latitude = location.getLatitude();
                        double longitude = location.getLongitude();

                        // 날씨 API 호출
                        fetchWeatherData(latitude, longitude);
                    } else {
                        showLoading(false);
                        Toast.makeText(this, "위치를 가져올 수 없습니다", Toast.LENGTH_SHORT).show();
                    }
                })
                .addOnFailureListener(this, e -> {
                    showLoading(false);
                    Toast.makeText(this, "위치 조회 실패: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                });
    }

    private void fetchWeatherData(double latitude, double longitude) {
        lastRequestedLatitude = latitude;
        lastRequestedLongitude = longitude;
        hasLastRequestedLocation = true;

        Call<WeatherResponse> call = ApiClient.getWeatherService().getWeather(
                latitude,
                longitude,
                API_KEY,
                "metric",
                "kr"
        );

        call.enqueue(new Callback<WeatherResponse>() {
            @Override
            public void onResponse(Call<WeatherResponse> call, Response<WeatherResponse> response) {
                showLoading(false);

                if (response.isSuccessful() && response.body() != null) {
                    WeatherResponse weatherData = response.body();
                    updateUI(weatherData);
                } else {
                    Toast.makeText(MainActivity.this,
                            "날씨 정보를 가져오는데 실패했습니다",
                            Toast.LENGTH_SHORT).show();
                }
            }

            @Override
            public void onFailure(Call<WeatherResponse> call, Throwable t) {
                showLoading(false);
                Toast.makeText(MainActivity.this,
                        "네트워크 오류: " + t.getMessage(),
                        Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void updateUI(WeatherResponse weatherData) {
        // 도시 이름 (기본값, 이후 지오코더로 한국어 주소 업데이트)
        lastFallbackCityName = weatherData.getCityName() != null ? weatherData.getCityName() : "";
        binding.cityName.setText(getCityFallbackName());
        updateCityNameUsingGeocoder();

        // 온도
        int temp = (int) weatherData.getMain().getTemp();
        binding.temperature.setText(temp + "°C");
        binding.character.setImageResource(getCharacterDrawableForTemp(temp));
        binding.clothes.setText(getClothesRecommendation(temp));

        // 체감 온도
        int feelsLike = (int) weatherData.getMain().getFeelsLike();
        binding.feelsLike.setText(feelsLike + "°C");

        // 습도
        binding.humidity.setText(weatherData.getMain().getHumidity() + "%");

        // 날씨 설명
        if (!weatherData.getWeather().isEmpty()) {
            binding.weatherDescription.setText(weatherData.getWeather().get(0).getDescription());

            // 날씨 아이콘 로드
            String iconCode = weatherData.getWeather().get(0).getIcon();
            String iconUrl = "https://openweathermap.org/img/wn/" + iconCode + "@4x.png";

            Picasso.get()
                    .load(iconUrl)
                    .into(binding.weatherIcon);
        }
    }

    private int getCharacterDrawableForTemp(int temperature) {
        if (temperature <= 0) {
            return R.drawable.degree0;
        } else if (temperature <= 4) {
            return R.drawable.degree4;
        } else if (temperature <= 8) {
            return R.drawable.degree5;
        } else if (temperature <= 11) {
            return R.drawable.degree9;
        } else if (temperature <= 16) {
            return R.drawable.degree12;
        } else if (temperature <= 19) {
            return R.drawable.degree17;
        } else if (temperature <= 22) {
            return R.drawable.degree20;
        } else if (temperature <= 27) {
            return R.drawable.degree23;
        } else {
            return R.drawable.degree28;
        }
    }

    private String getClothesRecommendation(int temperature) {
        if (temperature <= 4) {
            return "옷추천: 패딩, 두꺼운코트, 목도리, " +
                    "기모제품";
        } else if (temperature <= 8) {
            return "옷추천: 코트, 가죽자켓, 히트텍, 니트, " +
                    "레깅스";
        } else if (temperature <= 11) {
            return "옷추천: 자켓, 트렌치코트, 야상, 니트, " +
                    "청바지, 스타킹";
        } else if (temperature <= 16) {
            return "옷추천: 자켓, 가디건, 야상, 스타킹, " +
                    "청바지, 면바지";
        } else if (temperature <= 19) {
            return "옷추천: 얇은 니트, 맨투맨, 가디건, 청바지";
        } else if (temperature <= 22) {
            return "옷추천: 얇은 가디건, 긴팔, 면바지, 청바지";
        } else if (temperature <= 27) {
            return "옷추천: 반팔, 얇은 셔츠, 반바지, 면바지";
        } else {
            return "옷추천: 민소매, 반팔, 반바지, 원피스";
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        geocodeExecutor.shutdownNow();
    }

    private void showLoading(boolean isLoading) {
        binding.progressBar.setVisibility(isLoading ? View.VISIBLE : View.GONE);
        binding.refreshButton.setEnabled(!isLoading);
    }

    private void updateCityNameUsingGeocoder() {
        if (!hasLastRequestedLocation) {
            binding.cityName.setText(getCityFallbackName());
            return;
        }

        geocodeExecutor.execute(() -> {
            try {
                Geocoder geocoder = new Geocoder(MainActivity.this, Locale.KOREAN);
                List<Address> results = geocoder.getFromLocation(lastRequestedLatitude, lastRequestedLongitude, 1);
                if (results != null && !results.isEmpty()) {
                    String displayName = buildKoreanCityDisplay(results.get(0));
                    runOnUiThread(() -> binding.cityName.setText(displayName));
                } else {
                    runOnUiThread(() -> binding.cityName.setText(getCityFallbackName()));
                }
            } catch (IOException e) {
                runOnUiThread(() -> binding.cityName.setText(getCityFallbackName()));
            }
        });
    }

    private String buildKoreanCityDisplay(Address address) {
        List<String> parts = new ArrayList<>();
        appendIfNotEmpty(parts, address.getAdminArea());
        appendIfNotEmpty(parts, address.getLocality());
        appendIfNotEmpty(parts, address.getSubLocality());

        if (parts.isEmpty()) {
            return getCityFallbackName();
        }
        return TextUtils.join(" ", parts);
    }

    private void appendIfNotEmpty(List<String> parts, String value) {
        if (!TextUtils.isEmpty(value) && !parts.contains(value)) {
            parts.add(value);
        }
    }

    private String getCityFallbackName() {
        return TextUtils.isEmpty(lastFallbackCityName) ? "도시 이름" : lastFallbackCityName;
    }

    private static class SavedLocation {
        private final String id;
        private final String label;
        private final double latitude;
        private final double longitude;
        private final long savedAt;

        SavedLocation(String id, String label, double latitude, double longitude, long savedAt) {
            this.id = id;
            this.label = label;
            this.latitude = latitude;
            this.longitude = longitude;
            this.savedAt = savedAt;
        }
    }
}