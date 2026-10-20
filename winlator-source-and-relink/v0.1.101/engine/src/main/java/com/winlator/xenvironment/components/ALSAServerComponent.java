/* Aura integration changes for 0.1.101, 2026-10-20.
 * Original notices and individual file licenses remain applicable.
 * See the accompanying NOTICE.md and MODIFICATIONS.tsv.
 */
package com.winlator.xenvironment.components;

import com.winlator.alsaserver.ALSAClient;
import com.winlator.alsaserver.ALSAClientConnectionHandler;
import com.winlator.alsaserver.ALSARequestHandler;
import com.winlator.xconnector.UnixSocketConfig;
import com.winlator.xconnector.XConnectorEpoll;
import com.winlator.xenvironment.EnvironmentComponent;

public class ALSAServerComponent extends EnvironmentComponent {
    private android.media.AudioManager audioManager;
    private final android.media.AudioDeviceCallback deviceCallback = new android.media.AudioDeviceCallback() {
        @Override public void onAudioDevicesAdded(android.media.AudioDeviceInfo[] devices) {
            for (android.media.AudioDeviceInfo device : devices) if (device.isSink()) {
                com.winlator.alsaserver.AlsaPlayback.routeGeneration.incrementAndGet(); break;
            }
        }
        @Override public void onAudioDevicesRemoved(android.media.AudioDeviceInfo[] devices) {
            for (android.media.AudioDeviceInfo device : devices) if (device.isSink()) {
                com.winlator.alsaserver.AlsaPlayback.routeGeneration.incrementAndGet(); break;
            }
        }
    };
    private XConnectorEpoll connector;
    private final UnixSocketConfig socketConfig;
    private final ALSAClient.Options options;

    public ALSAServerComponent(UnixSocketConfig socketConfig, ALSAClient.Options options) {
        this.socketConfig = socketConfig;
        this.options = options;
    }

    @Override
    public void start() {
        if (connector != null) return;
        ALSAClient.assignFramesPerBuffer(environment.getContext());
        connector = new XConnectorEpoll(socketConfig, new ALSAClientConnectionHandler(options), new ALSARequestHandler());
        connector.setMultithreadedClients(true);
        connector.start();
        if (options.reflector) {
            audioManager = (android.media.AudioManager)environment.getContext().getSystemService(android.content.Context.AUDIO_SERVICE);
            audioManager.registerAudioDeviceCallback(deviceCallback, new android.os.Handler(android.os.Looper.getMainLooper()));
        }
    }

    @Override
    public void stop() {
        if (audioManager != null) { audioManager.unregisterAudioDeviceCallback(deviceCallback); audioManager = null; }
        if (connector != null) {
            connector.destroy();
            connector = null;
        }
    }
}
