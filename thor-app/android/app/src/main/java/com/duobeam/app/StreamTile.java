package com.duobeam.app;

import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/** Quick Settings tile that starts/stops the stream without opening the app. */
public class StreamTile extends TileService implements StreamClient.Listener {

    private StreamClient client;

    @Override
    public void onStartListening() {
        client = StreamClient.get(this);
        client.addListener(this);
        refresh();
    }

    @Override
    public void onStopListening() {
        if (client != null) client.removeListener(this);
    }

    @Override
    public void onClick() {
        client = StreamClient.get(this);
        client.toggle();
        refresh();
    }

    @Override
    public void onChanged() {
        refresh();
    }

    private void refresh() {
        Tile t = getQsTile();
        if (t == null || client == null) return;
        boolean on = client.isStreaming();
        t.setState(client.ready() ? (on ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE) : Tile.STATE_INACTIVE);
        t.setLabel("DuoBeam");
        t.setSubtitle(!client.ready() ? L.s("tile_setup") : on ? client.settings().layoutName() : L.s("tile_off"));
        t.updateTile();
    }
}
