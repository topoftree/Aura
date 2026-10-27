/* Aura integration changes for 0.1.149, 2026-10-27.
 * Original notices and individual file licenses remain applicable.
 * See the accompanying NOTICE.md and MODIFICATIONS.tsv.
 */
package com.winlator.contentdialog;

import android.content.Context;
import android.view.View;
import android.widget.Spinner;

import com.winlator.R;
import com.winlator.container.DXWrappers;
import com.winlator.core.AppUtils;
import com.winlator.core.DefaultVersion;
import com.winlator.core.DxvkConfig;
import com.winlator.core.EnvVars;
import com.winlator.core.FileUtils;
import com.winlator.core.GeneralComponents;
import com.winlator.core.KeyValueSet;
import com.winlator.core.StringUtils;
import com.winlator.widget.GPUCardAdapter;
import com.winlator.xenvironment.RootFS;

import java.io.File;

public class DXVKConfigDialog extends ContentDialog {
    public DXVKConfigDialog(String graphicsDriver, final View anchor) {
        super(anchor.getContext(), R.layout.dxvk_config_dialog);
        setIcon(R.drawable.icon_display_settings);
        Context context = anchor.getContext();
        setTitle("DXVK "+context.getString(R.string.configuration));

        final Spinner sVersion = findViewById(R.id.SVersion);
        final Spinner sFramerate = findViewById(R.id.SFramerate);
        final Spinner sMaxDeviceMemory = findViewById(R.id.SMaxDeviceMemory);
        final Spinner sCustomDevice = findViewById(R.id.SCustomDevice);
        final Spinner sDDrawWrapper = findViewById(R.id.SDDrawWrapper);

        KeyValueSet config = new KeyValueSet(anchor.getTag());
        AppUtils.setSpinnerSelectionFromIdentifier(sFramerate, config.get("framerate", "0"));
        AppUtils.setSpinnerSelectionFromMemorySize(sMaxDeviceMemory, config.get("maxDeviceMemory", "0"));
        AppUtils.setSpinnerSelectionFromIdentifier(sDDrawWrapper, config.get("ddrawWrapper", DXWrappers.WINED3D));

        String version = config.get("version");
        String defaultVersion = DefaultVersion.DXVK(graphicsDriver);
        GeneralComponents.initViews(GeneralComponents.Type.DXVK, findViewById(R.id.DXVKToolbox), sVersion, version, defaultVersion);

        GPUCardAdapter adapter = new GPUCardAdapter(context, android.R.layout.simple_spinner_dropdown_item, R.string.none);
        sCustomDevice.setAdapter(adapter);

        String customDevice = config.get("customDevice");
        if (customDevice.contains(":")) {
            try {
                int deviceId = Integer.parseInt(customDevice.split(":")[0], 16);
                sCustomDevice.setSelection(adapter.getPositionByDeviceId(deviceId));
            }
            catch (NumberFormatException e) {}
        }

        setOnConfirmCallback(() -> {
            KeyValueSet newConfig = new KeyValueSet();
            newConfig.put("version", sVersion.getSelectedItem().toString());
            newConfig.put("framerate", StringUtils.parseNumber(sFramerate.getSelectedItem()));
            newConfig.put("maxDeviceMemory", StringUtils.parseMemorySize(sMaxDeviceMemory.getSelectedItem()));

            String ddrawWrapper = StringUtils.parseIdentifier(sDDrawWrapper.getSelectedItem());
            if (!ddrawWrapper.equals(DXWrappers.WINED3D)) newConfig.put("ddrawWrapper", ddrawWrapper);

            GPUCardAdapter.GPUCard gpuCard = (GPUCardAdapter.GPUCard)sCustomDevice.getSelectedItem();
            if (gpuCard.deviceId > 0) {
                newConfig.put("customDevice", String.format("%04x", gpuCard.deviceId)+":"+String.format("%04x", gpuCard.vendorId)+":"+gpuCard.name);
            }

            anchor.setTag(newConfig.toString());
        });
    }

    public static void setEnvVars(Context context, KeyValueSet config, EnvVars envVars) {
        if (config.get("version").contains("async")) envVars.put("DXVK_ASYNC", "1");
        envVars.put("DXVK_STATE_CACHE_PATH", RootFS.getDosUserCachePath());
        envVars.put("DXVK_LOG_LEVEL", "none");

        File rootDir = RootFS.find(context).getRootDir();
        File dxvkConfigFile = new File(rootDir, RootFS.USER_CONFIG_PATH+"/dxvk.conf");

        FileUtils.delete(dxvkConfigFile);
        String content = DxvkConfig.content(config);
        if (FileUtils.writeString(dxvkConfigFile, content)) {
            envVars.put("DXVK_CONFIG_FILE", RootFS.getDosUserConfigPath()+"\\dxvk.conf");
        }
    }

}
