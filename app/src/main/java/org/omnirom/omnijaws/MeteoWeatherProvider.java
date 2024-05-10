/*
 * Copyright (C) 2024 Yet Another AOSP Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.omnirom.omnijaws;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.omnirom.omnijaws.WeatherInfo.DayForecast;

import android.content.Context;
import android.content.res.Resources;
import android.location.Location;
import android.text.TextUtils;
import android.util.Log;

public class MeteoWeatherProvider extends AbstractWeatherProvider {
    private static final String TAG = "MeteoWeatherProvider";

    private static final String CURRENT_ARGS =
            "&current=temperature_2m,relative_humidity_2m,is_day,weather_code,wind_speed_10m,wind_direction_10m";
    private static final String DAILY_ARGS =
            "&daily=weather_code,temperature_2m_max,temperature_2m_min";
    private static final String IMPERIAL_ARGS =
            "&temperature_unit=fahrenheit&wind_speed_unit=mph&precipitation_unit=inch";
    private static final String URL_WEATHER =
            "https://api.open-meteo.com/v1/forecast?%s" + CURRENT_ARGS + DAILY_ARGS + "%s";

    public MeteoWeatherProvider(Context context) {
        super(context);
    }

    public WeatherInfo getCustomWeather(String id, boolean metric) {
        return handleWeatherRequest(id, metric);
    }

    public WeatherInfo getLocationWeather(Location location, boolean metric) {
        String coordinates = String.format(Locale.US, PART_COORDINATES,
                location.getLatitude(), location.getLongitude());
        return handleWeatherRequest(coordinates, metric);
    }

    private WeatherInfo handleWeatherRequest(String selection, boolean metric) {
        String units = metric ? "" : IMPERIAL_ARGS;
        String coordinates = selection.replace("lat", "latitude").replace("lon", "longitude");
        String conditionUrl = String.format(Locale.US, URL_WEATHER, coordinates, units);
        String conditionResponse = retrieve(conditionUrl);
        if (conditionResponse == null) {
            return null;
        }
        log(TAG, "Condition URL = " + conditionUrl + " returning a response of " + conditionResponse);

        try {
            JSONObject conditions = new JSONObject(conditionResponse);
            JSONObject weather = conditions.getJSONObject("current");
            ArrayList<DayForecast> forecasts =
                    parseForecasts(conditions.getJSONObject("daily"), metric);
            String city = getWeatherDataLocality(selection);
            boolean isDay = weather.getInt("is_day") != 0;

            WeatherInfo w = new WeatherInfo(mContext, selection, city,
                    /* condition */ getConditionForCode(weather.getInt("weather_code")),
                    /* conditionCode */ mapConditionIconToCode(
                            weather.getInt("weather_code"), isDay),
                    /* temperature */ (float) weather.getDouble("temperature_2m"),
                    /* humidity */ (float) weather.getInt("relative_humidity_2m"),
                    /* wind */ (float) weather.getDouble("wind_speed_10m"),
                    /* windDir */ weather.getInt("wind_direction_10m"),
                    metric,
                    forecasts,
                    System.currentTimeMillis());

            log(TAG, "Weather updated: " + w);
            return w;
        } catch (JSONException e) {
            Log.w(TAG, "Received malformed weather data (selection = " + selection
                    + ", metric = " + metric + ")", e);
        }

        return null;
    }

    private ArrayList<DayForecast> parseForecasts(JSONObject forecasts, boolean metric) throws JSONException {
        ArrayList<DayForecast> result = new ArrayList<DayForecast>();
        int count = forecasts.getJSONArray("time").length();

        if (count == 0) {
            throw new JSONException("Empty forecasts array");
        }
        for (int i = 0; i < count; i++) {
            String day = getDay(i);
            DayForecast item = null;
            try {
                item = new DayForecast(
                        /* low */(float) forecasts.getJSONArray("temperature_2m_min").getDouble(i),
                        /* high */ (float) forecasts.getJSONArray("temperature_2m_max").getDouble(i),
                        /* condition */ getConditionForCode(
                                forecasts.getJSONArray("weather_code").getInt(i)),
                        /* conditionCode */ mapConditionIconToCode(
                                forecasts.getJSONArray("weather_code").getInt(i), true),
                        day,
                        metric);
            } catch (JSONException e) {
                Log.w(TAG, "Invalid forecast for day " + i + " creating dummy", e);
                item = new DayForecast(
                        /* low */ 0,
                        /* high */ 0,
                        /* condition */ "",
                        /* conditionCode */ -1,
                        "NaN",
                        metric);
            }
            result.add(item);
        }
        // clients assume there are 5  entries - so fill with dummy if needed
        if (result.size() < 5) {
            for (int i = result.size(); i < 5; i++) {
                Log.w(TAG, "Missing forecast for day " + i + " creating dummy");
                DayForecast item = new DayForecast(
                        /* low */ 0,
                        /* high */ 0,
                        /* condition */ "",
                        /* conditionCode */ -1,
                        "NaN",
                        metric);
                result.add(item);
            }
        }
        return result;
    }

    private static String getConditionForCode(int code) {
        if (!WEATHER_CONDITION_MAPPING.containsKey(code))
            code = 0;
        return WEATHER_CONDITION_MAPPING.get(code);
    }

    private static final HashMap<Integer, String> WEATHER_CONDITION_MAPPING = new HashMap<>();
	static {
		WEATHER_CONDITION_MAPPING.put(0, "Clear Sky");
		WEATHER_CONDITION_MAPPING.put(1, "Mostly Clear");
		WEATHER_CONDITION_MAPPING.put(2, "Partly Cloudy");
		WEATHER_CONDITION_MAPPING.put(3, "Cloudy");
        WEATHER_CONDITION_MAPPING.put(45, "Foggy");
        WEATHER_CONDITION_MAPPING.put(48, "Foggy");
        WEATHER_CONDITION_MAPPING.put(51, "Light Drizzle");
        WEATHER_CONDITION_MAPPING.put(53, "Moderate Drizzle");
        WEATHER_CONDITION_MAPPING.put(55, "Heavy Drizzle");
        WEATHER_CONDITION_MAPPING.put(56, "Freezing Drizzle");
        WEATHER_CONDITION_MAPPING.put(57, "Heavy Freezing Drizzle");
        WEATHER_CONDITION_MAPPING.put(61, "Light Rain");
        WEATHER_CONDITION_MAPPING.put(63, "Rain");
        WEATHER_CONDITION_MAPPING.put(65, "Heavy Rain");
		WEATHER_CONDITION_MAPPING.put(80, "Light Showers");
        WEATHER_CONDITION_MAPPING.put(81, "Moderate Showers");
        WEATHER_CONDITION_MAPPING.put(82, "Heavy Showers");
        WEATHER_CONDITION_MAPPING.put(85, "Light Snow");
        WEATHER_CONDITION_MAPPING.put(86, "Heavy Snow");
        WEATHER_CONDITION_MAPPING.put(95, "Thunderstorms");
        WEATHER_CONDITION_MAPPING.put(96, "Thunderstorms and Hail");
        WEATHER_CONDITION_MAPPING.put(99, "Thunderstorms and Heavy Hail");
    }

    private int mapConditionIconToCode(int code, boolean isDay) {
        switch (code) {
            // Thunderstorms
            case 99:       // thunderstorm
            case 96:       // thunderstorm and hail
            case 95:       // thunderstorm and heavy hail
                return 4;

            // Snow
            case 85:
                return 14; // light snow
            case 86:
                return 41; // heavy snow

            // Drizzle
            case 51:       // light drizzle
            case 53:       // drizzle
            case 55:       // heavy drizzle
            case 56:       // freezing drizzle
            case 57:       // heavy freezing drizzle
                return 9;

            // Rain
            case 61:       // light rain
            case 63:       // rain
            case 80:       // light shower
            case 81:       // shower
                return 11;
            case 82:       // heavy shower
            case 65:       // heavy rain
                return 12;

            // Atmosphere
            case 45:       // fog
            case 48:       // fog
                return 20;

            // clouds
            case 0:        // clear sky
                return isDay ? 32 : 31; // day or night
            case 1:        // mostly clear
                return isDay ? 34 : 33; // day or night
            case 2:        // partly cloudy
                return isDay ? 28 : 27; // day or night
            case 3:        // cloudy
                return isDay ? 30 : 29; // day or night
        }

        return -1;
    }

    public boolean shouldRetry() {
        return false;
    }
}
