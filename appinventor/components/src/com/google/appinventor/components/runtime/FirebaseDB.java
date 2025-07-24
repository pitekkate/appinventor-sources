// -*- mode: java; c-basic-offset: 2; -*-
// Copyright 2024 MIT, All rights reserved
// Released under the Apache License, Version 2.0
// http://www.apache.org/licenses/LICENSE-2.0

package com.google.appinventor.components.runtime;

import android.app.Activity;
import android.os.Handler;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.appinventor.components.annotations.*;
import com.google.appinventor.components.common.ComponentCategory;
import com.google.appinventor.components.common.PropertyTypeConstants;
import com.google.appinventor.components.common.YaVersion;
import com.google.appinventor.components.runtime.errors.YailRuntimeError;
import com.google.appinventor.components.runtime.util.JsonUtil;
import com.google.appinventor.components.runtime.util.SdkLevel;
import com.google.appinventor.components.runtime.util.YailList;

import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.database.*;

import org.json.JSONException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The FirebaseDB component communicates with Firebase Realtime Database
 * to store and retrieve information. This version uses Firebase SDK 10+.
 */
@DesignerComponent(
    version = YaVersion.FIREBASE_COMPONENT_VERSION,
    description = "Non-visible component that communicates with Firebase Realtime Database.",
    designerHelpDescription = "Non-visible component that communicates with Firebase Realtime Database.",
    category = ComponentCategory.EXPERIMENTAL,
    nonVisible = true,
    androidMinSdk = 16,
    iconName = "images/firebaseDB.png"
)
@SimpleObject
@UsesPermissions(permissionNames = "android.permission.INTERNET")
@UsesLibraries(libraries = "firebase-database-20.3.0.jar, firebase-app-20.4.0.jar")
public class FirebaseDB extends AndroidNonvisibleComponent implements Component {

    private static final String LOG_TAG = "FirebaseDB";

    private String databaseUrl = null;
    private String projectBucket = "";
    private String developerBucket = "";
    private boolean useDefault = true;
    private boolean persist = false;

    private final Activity activity;
    private DatabaseReference myRef;
    private ValueEventListener valueListener;
    private FirebaseAuth.AuthStateListener authListener;

    private Handler androidUIHandler;

    // Static initialization flag
    private static boolean isInitialized = false;

    /**
     * Creates a new FirebaseDB component.
     *
     * @param container the Form that this component is contained in.
     */
    public FirebaseDB(ComponentContainer container) {
        super(container.$form());
        this.activity = container.$context();
        this.androidUIHandler = new Handler();

        // Initialize Firebase
        if (!isInitialized) {
            FirebaseApp.initializeApp(activity);
            isInitialized = true;
        }

        setupListeners();
    }

    private void setupListeners() {
        valueListener = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                final String key = snapshot.getKey();
                final Object value = snapshot.getValue();
                androidUIHandler.post(() -> DataChanged(key, value));
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                androidUIHandler.post(() -> FirebaseError(error.getMessage()));
            }
        };

        authListener = firebaseAuth -> {
            if (firebaseAuth.getCurrentUser() == null) {
                // Re-auth if needed
            }
        };
    }

    /**
     * Initialize: Called after all properties are set.
     */
    public void Initialize() {
        connectFirebase();
    }

    // ========== PROPERTIES ==========

    @SimpleProperty(category = PropertyCategory.BEHAVIOR)
    public String DatabaseURL() {
        return useDefault ? "DEFAULT" : databaseUrl;
    }

    @DesignerProperty(editorType = PropertyTypeConstants.PROPERTY_TYPE_FIREBASE_URL, defaultValue = "DEFAULT")
    @SimpleProperty
    public void DatabaseURL(String url) {
        if ("DEFAULT".equals(url)) {
            useDefault = true;
            // Set default URL if available
            // defaultURL = ... from config
        } else {
            useDefault = false;
            this.databaseUrl = url.endsWith("/") ? url : url + "/";
        }
        connectFirebase();
    }

    @DesignerProperty(editorType = PropertyTypeConstants.PROPERTY_TYPE_STRING)
    @SimpleProperty
    public void DeveloperBucket(String bucket) {
        this.developerBucket = bucket;
        connectFirebase();
    }

    @SimpleProperty
    public String DeveloperBucket() {
        return developerBucket;
    }

    @DesignerProperty(editorType = PropertyTypeConstants.PROPERTY_TYPE_STRING, defaultValue = "")
    @SimpleProperty
    public void ProjectBucket(String bucket) {
        if (!projectBucket.equals(bucket)) {
            this.projectBucket = bucket;
            connectFirebase();
        }
    }

    @SimpleProperty
    public String ProjectBucket() {
        return projectBucket;
    }

    @DesignerProperty(editorType = PropertyTypeConstants.PROPERTY_TYPE_BOOLEAN, defaultValue = "False")
    @SimpleProperty
    public void Persist(boolean value) {
        if (persist != value) {
            if (isInitialized && myRef != null) {
                myRef.keepSynced(value);
            }
            persist = value;
        }
    }

    // ========== CORE METHODS ==========

    private void connectFirebase() {
        if (activity == null) return;

        if (myRef != null) {
            myRef.removeEventListener(valueListener);
        }

        String path = useDefault
            ? "developers/" + developerBucket + "/" + projectBucket
            : projectBucket;

        FirebaseDatabase database = FirebaseDatabase.getInstance();
        if (databaseUrl != null) {
            database = FirebaseDatabase.getInstance(databaseUrl);
        }

        myRef = database.getReference(path);

        if (persist) {
            myRef.keepSynced(true);
        }

        myRef.addValueEventListener(valueListener);
    }

    @SimpleFunction
    public void StoreValue(String tag, Object valueToStore) {
        try {
            if (valueToStore != null) {
                valueToStore = JsonUtil.getJsonRepresentation(valueToStore);
            }
            myRef.child(tag).setValue(valueToStore);
        } catch (JSONException e) {
            throw new YailRuntimeError("Value failed to convert to JSON.", "JSON Creation Error.");
        }
    }

    @SimpleFunction
    public void GetValue(String tag, Object valueIfTagNotThere) {
        myRef.child(tag).addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                AtomicReference<Object> value = new AtomicReference<>();
                try {
                    if (snapshot.exists()) {
                        value.set(snapshot.getValue());
                    } else {
                        value.set(JsonUtil.getJsonRepresentation(valueIfTagNotThere));
                    }
                } catch (JSONException e) {
                    throw new YailRuntimeError("Value failed to convert to JSON.", "JSON Creation Error.");
                }
                androidUIHandler.post(() -> GotValue(tag, value.get()));
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                androidUIHandler.post(() -> FirebaseError(error.getMessage()));
            }
        });
    }

    @SimpleFunction
    public void ClearTag(String tag) {
        myRef.child(tag).removeValue();
    }

    // ========== EVENTS ==========

    @SimpleEvent
    public void GotValue(String tag, Object value) {
        try {
            if (value != null && value instanceof String) {
                value = JsonUtil.getObjectFromJson((String) value, true);
            }
        } catch (JSONException e) {
            throw new YailRuntimeError("Value failed to convert from JSON.", "JSON Retrieval Error.");
        }
        EventDispatcher.dispatchEvent(this, "GotValue", tag, value);
    }

    @SimpleEvent
    public void DataChanged(String tag, Object value) {
        try {
            if (value != null && value instanceof String) {
                value = JsonUtil.getObjectFromJson((String) value, true);
            }
        } catch (JSONException e) {
            throw new YailRuntimeError("Value failed to convert from JSON.", "JSON Retrieval Error.");
        }
        EventDispatcher.dispatchEvent(this, "DataChanged", tag, value);
    }

    @SimpleEvent
    public void FirebaseError(String message) {
        android.util.Log.e(LOG_TAG, message);
        boolean dispatched = EventDispatcher.dispatchEvent(this, "FirebaseError", message);
        if (!dispatched) {
            Notifier.oneButtonAlert(form, message, "FirebaseError", "Continue");
        }
    }

    // ========== LIST MANAGEMENT ==========

    @SimpleFunction
    public void AppendValue(String tag, Object valueToAdd) {
        DatabaseReference ref = myRef.child(tag);
        ref.runTransaction(new Transaction.Handler() {
            @NonNull
            @Override
            public Transaction.Result doTransaction(@NonNull MutableData currentData) {
                Object value = currentData.getValue();
                List<Object> list;
                if (value == null || !(value instanceof List)) {
                    list = new ArrayList<>();
                } else {
                    list = (List<Object>) value;
                }
                list.add(valueToAdd);
                currentData.setValue(list);
                return Transaction.success(currentData);
            }

            @Override
            public void onComplete(@Nullable DatabaseError error, boolean committed, @Nullable DataSnapshot currentData) {
                if (error != null) {
                    androidUIHandler.post(() -> FirebaseError(error.getMessage()));
                }
            }
        });
    }

    @SimpleFunction
    public void RemoveFirst(String tag) {
        DatabaseReference ref = myRef.child(tag);
        ref.runTransaction(new Transaction.Handler() {
            @NonNull
            @Override
            public Transaction.Result doTransaction(@NonNull MutableData currentData) {
                Object value = currentData.getValue();
                if (!(value instanceof List)) {
                    return Transaction.abort();
                }
                List<Object> list = (List<Object>) value;
                if (list.isEmpty()) {
                    return Transaction.abort();
                }
                Object first = list.remove(0);
                currentData.setValue(list);
                return Transaction.success(currentData, first);
            }

            @Override
            public void onComplete(@Nullable DatabaseError error, boolean committed, @Nullable DataSnapshot currentData) {
                if (committed && currentData != null) {
                    Object first = currentData.getPreviousValue();
                    androidUIHandler.post(() -> FirstRemoved(first));
                } else if (error != null) {
                    androidUIHandler.post(() -> FirebaseError(error.getMessage()));
                }
            }
        });
    }

    @SimpleEvent
    public void FirstRemoved(Object value) {
        EventDispatcher.dispatchEvent(this, "FirstRemoved", value);
    }

    @SimpleFunction
    public void GetTagList() {
        myRef.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                List<String> keys = new ArrayList<>();
                for (DataSnapshot child : snapshot.getChildren()) {
                    keys.add(child.getKey());
                }
                androidUIHandler.post(() -> TagList(keys));
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                // Ignore
            }
        });
    }

    @SimpleEvent
    public void TagList(List<String> value) {
        EventDispatcher.dispatchEvent(this, "TagList", value);
    }

    // ========== AUTH ==========

    @SimpleFunction
    public void Unauthenticate() {
        // Firebase Auth
        // FirebaseAuth.getInstance().signOut();
    }

    @DesignerProperty(editorType = PropertyTypeConstants.PROPERTY_TYPE_STRING)
    @SimpleProperty(category = PropertyCategory.BEHAVIOR, userVisible = false)
    public void DefaultURL(String url) {
        // Handle default URL if needed
    }
}
