package com.radiorubka.wright;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.Log;

/**
 * wRight's end of wFabia's vehicle pipe (docs/VEHICLE_PIPE.md in the wFabia repo): the lights and dimmer level that wFabia
 * reads from the car's CAN bus. wRight never touches the CAN adapter; wFabia stays its only owner.
 *
 * Binds to de.wfabia's VehiclePipeService with a plain Binder and Parcels, says HELLO with a callback binder, and gets STATE
 * messages. Reconnects with exponential backoff (1 s .. 30 s) when wFabia is not running or not yet allowed to talk to us
 * (the user has to switch "Allow wRight" on in wFabia). Every STATE is checked against the uid of de.wfabia.
 */
public class VehiclePipeClient {
    private static final String TAG = "wRight_Pipe";

    private static final String WFABIA = "de.wfabia";
    private static final String ACTION = "de.wfabia.action.VEHICLE_PIPE";
    private static final String DESCRIPTOR = "de.wfabia.IVehiclePipe";
    private static final String CB_DESCRIPTOR = "de.wfabia.IVehiclePipeCallback";
    private static final int VERSION = 1;
    private static final int T_HELLO = IBinder.FIRST_CALL_TRANSACTION,
            T_REQUEST_STATE = T_HELLO + 1, T_APPLIED = T_HELLO + 2, T_BYE = T_HELLO + 3, T_LOCATION = T_HELLO + 4;
    private static final int CB_STATE = IBinder.FIRST_CALL_TRANSACTION;
    private static final int R_OK = 0, R_REJECTED = 1, R_MODULE_OFF = 2;

    /** no state for this long: treat the pipe as invalid (wFabia sends a keepalive at least every 2 s) */
    public static final long STALE_MS = 6000;

    public interface Listener {
        /** called on the handler thread; valid = false means "ignore lightsOn and level" */
        void onState(boolean valid, boolean lightsOn, int level);
    }

    private final Context ctx;
    private final Handler handler;
    private final Listener listener;

    private IBinder service;
    private boolean bound;
    private boolean stopped = true;
    private long backoffMs = 1000;
    private volatile long lastStateAt;
    private volatile String status = "not started";
    private long lastAppliedAt;

    private final Binder callback = new Binder() {
        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
            if (code != CB_STATE) return super.onTransact(code, data, reply, flags);
            data.enforceInterface(CB_DESCRIPTOR);
            if (!fromWfabia(Binder.getCallingUid())) return true;          // anyone else who got hold of this binder is ignored
            int v = data.readInt();
            final boolean valid = data.readInt() != 0;
            final boolean lights = data.readInt() != 0;
            final int level = data.readInt();
            data.readLong();
            if (v < 1) return true;
            lastStateAt = SystemClock.elapsedRealtime();
            handler.post(() -> listener.onState(valid, lights, level));
            return true;
        }
    };

    private final ServiceConnection conn = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            handler.post(() -> hello(binder));
        }

        @Override public void onServiceDisconnected(ComponentName name) {
            handler.post(() -> lost("wFabia went away"));
        }

        @Override public void onBindingDied(ComponentName name) {
            handler.post(() -> lost("binding died"));
        }

        @Override public void onNullBinding(ComponentName name) {
            handler.post(() -> lost("wFabia has no pipe"));
        }
    };

    private final Runnable rebind = this::connect;

    public VehiclePipeClient(Context context, Handler handler, Listener listener) {
        this.ctx = context.getApplicationContext();
        this.handler = handler;
        this.listener = listener;
    }

    /** the pipe is connected and a state arrived recently */
    public boolean isFresh() {
        return service != null && SystemClock.elapsedRealtime() - lastStateAt < STALE_MS;
    }

    public String status() { return status; }

    public void start() {
        handler.post(() -> {
            stopped = false;
            connect();
        });
    }

    public void stop() {
        handler.post(() -> {
            stopped = true;
            handler.removeCallbacks(rebind);
            sendBye();
            disconnect();
            status = "stopped";
        });
    }

    private void connect() {
        if (stopped || bound) return;
        Intent i = new Intent(ACTION).setPackage(WFABIA);
        try {
            bound = ctx.bindService(i, conn, Context.BIND_AUTO_CREATE);
        } catch (SecurityException e) {
            bound = false;
        }
        if (!bound) {
            status = "wFabia not installed";
            scheduleRebind(30_000);
        }
    }

    private void hello(IBinder binder) {
        if (stopped) return;
        Parcel d = Parcel.obtain(), r = Parcel.obtain();
        try {
            d.writeInterfaceToken(DESCRIPTOR);
            d.writeInt(VERSION);
            d.writeInt(1);                                   // wants: bit0 = dimmer
            d.writeString(ctx.getPackageName());
            d.writeStrongBinder(callback);
            binder.transact(T_HELLO, d, r, 0);
            r.readException();
            int result = r.readInt();
            r.readInt();                                     // server version
            if (result == R_OK) {
                service = binder;
                backoffMs = 1000;
                status = "connected";
                Log.i(TAG, "connected to wFabia");
            } else {
                status = result == R_REJECTED ? "wFabia: allow wRight in the vehicle pipe card" : "wFabia: vehicle pipe module is off";
                Log.i(TAG, status);
                disconnect();
                scheduleRebind(Math.max(backoffMs, 10_000));
            }
        } catch (RemoteException | RuntimeException e) {
            Log.w(TAG, "hello failed", e);
            lost("hello failed");
        } finally {
            d.recycle();
            r.recycle();
        }
    }

    private void lost(String why) {
        service = null;
        status = why;
        disconnect();
        listener.onState(false, false, 0);
        scheduleRebind(backoffMs);
        backoffMs = Math.min(30_000, backoffMs * 2);
    }

    private void disconnect() {
        if (bound) {
            try {
                ctx.unbindService(conn);
            } catch (IllegalArgumentException ignored) {
                // not bound any more
            }
            bound = false;
        }
        service = null;
    }

    private void scheduleRebind(long ms) {
        handler.removeCallbacks(rebind);
        if (!stopped) handler.postDelayed(rebind, ms);
    }

    private void sendBye() {
        IBinder s = service;
        if (s == null) return;
        Parcel d = Parcel.obtain();
        try {
            d.writeInterfaceToken(DESCRIPTOR);
            s.transact(T_BYE, d, null, IBinder.FLAG_ONEWAY);
        } catch (RemoteException ignored) {
            // wFabia is gone anyway
        } finally {
            d.recycle();
        }
    }

    /** informational: what wRight is driving now. Rate limited to one call per 2 s. */
    public void reportApplied(int display255, int buttonsRgb, boolean active) {
        IBinder s = service;
        long now = SystemClock.elapsedRealtime();
        if (s == null || now - lastAppliedAt < 2000) return;
        lastAppliedAt = now;
        Parcel d = Parcel.obtain();
        try {
            d.writeInterfaceToken(DESCRIPTOR);
            d.writeInt(display255);
            d.writeInt(buttonsRgb);
            d.writeInt(active ? 1 : 0);
            s.transact(T_APPLIED, d, null, IBinder.FLAG_ONEWAY);
        } catch (RemoteException ignored) {
            // the next state or rebind will sort it out
        } finally {
            d.recycle();
        }
    }

    /** a location fix for wFabia's speed row (wRight is the only app that asks for fixes). Oneway, dropped when not connected. */
    public boolean sendLocation(boolean hasSpeed, float speedMs, float accuracy, String provider) {
        IBinder s = service;
        if (s == null) return false;
        Parcel d = Parcel.obtain();
        try {
            d.writeInterfaceToken(DESCRIPTOR);
            d.writeInt(VERSION);
            d.writeInt(hasSpeed ? 1 : 0);
            d.writeFloat(speedMs);
            d.writeFloat(accuracy);
            d.writeString(provider);
            s.transact(T_LOCATION, d, null, IBinder.FLAG_ONEWAY);
            return true;
        } catch (RemoteException ignored) {
            // the next state or rebind will sort it out
            return false;
        } finally {
            d.recycle();
        }
    }

    private boolean fromWfabia(int uid) {
        try {
            return ctx.getPackageManager().getPackageUid(WFABIA, 0) == uid;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }
}
