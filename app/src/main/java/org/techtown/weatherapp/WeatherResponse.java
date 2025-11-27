package org.techtown.weatherapp;

import com.google.gson.annotations.SerializedName;
import java.util.List;

public class WeatherResponse {
    @SerializedName("name")
    private String cityName;

    @SerializedName("main")
    private Main main;

    @SerializedName("weather")
    private List<Weather> weather;

    @SerializedName("wind")
    private Wind wind;

    // Getters
    public String getCityName() { return cityName; }
    public Main getMain() { return main; }
    public List<Weather> getWeather() { return weather; }
    public Wind getWind() { return wind; }
}

class Main {
    @SerializedName("temp")
    private double temp;

    @SerializedName("feels_like")
    private double feelsLike;

    @SerializedName("humidity")
    private int humidity;

    @SerializedName("pressure")
    private int pressure;

    // Getters
    public double getTemp() { return temp; }
    public double getFeelsLike() { return feelsLike; }
    public int getHumidity() { return humidity; }
    public int getPressure() { return pressure; }
}

class Weather {
    @SerializedName("id")
    private int id;

    @SerializedName("main")
    private String main;

    @SerializedName("description")
    private String description;

    @SerializedName("icon")
    private String icon;

    // Getters
    public int getId() { return id; }
    public String getMain() { return main; }
    public String getDescription() { return description; }
    public String getIcon() { return icon; }
}

class Wind {
    @SerializedName("speed")
    private double speed;

    // Getter
    public double getSpeed() { return speed; }
}