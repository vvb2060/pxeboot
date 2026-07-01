# Android PXE Boot Server

Android PXE Boot Server is an Android application that integrates ProxyDHCP, TFTP, and HTTP services to provide a temporary PXE environment on a home network.
It is intended for recovery and maintenance scenarios, such as booting Windows PE or other rescue environments.

## Overview

The application is designed for short-lived PXE deployments in a local network where a dedicated boot server is not available.
A rooted Android device can temporarily act as the network boot endpoint and serve iPXE scripts, bootloaders, and ISO-backed resources required for rescue workflows.

The app is **not** a full DHCP server. It listens for DHCP traffic and responds with ProxyDHCP information so PXE-capable clients can discover the boot resources exposed by the device.

## Usage

1. Connect the computer to the home gateway by **wired Ethernet**.
2. Connect the Android device to the **same gateway over Wi-Fi**.
3. Ensure the computer and the Android device can reach each other directly.
4. Verify that the gateway does **not** enable AP isolation or DHCP anti-spoofing / DHCP protection features that would block DHCP listening behavior.
5. Verify that the gateway or firewall does not block the ports used by the app:

| Protocol | Port | Purpose                |
|----------|------|------------------------|
| UDP      | 67   | DHCP request listening |
| UDP      | 69   | TFTP                   |
| TCP      | 80   | HTTP                   |
| UDP      | 4011 | ProxyDHCP              |

The HTTP port is configurable. UDP ports `67` and `69` require [privileged access](https://issuetracker.google.com/issues/218578943), so the Android device must be rooted.

To start a boot environment:

1. Prepare a Windows PE ISO in the device storage, or use an ISO hosted on a NAS or on the internet.
2. Open the app.
3. Edit the bundled iPXE script as needed.
4. Start the service.

## Supported Platforms

The app supports the following PXE client platforms:

- x86_64 BIOS
- x86_64 UEFI
- arm64 UEFI

UEFI HTTP Boot does not use ProxyDHCP, so UEFI HTTP Boot provisioning is only available through DHCP.
If your firmware provides a manual UEFI HTTP Boot configuration screen, you can still enter the URL of this app's HTTP service manually.

## Secure Boot

The bundled UEFI boot chain supports Secure Boot with both Microsoft 2011 and Microsoft 2023 certificates.
However, the operating system image or ISO you boot must also be compatible with Secure Boot.

Many custom Windows PE and Linux images do **not** yet support the Microsoft 2023 certificate chain.
Even if the application-side bootloader works correctly, the final boot image may still fail Secure Boot validation.

## iPXE Script

The built-in `autoexec.ipxe` script contains three example entries:

1. A Windows PE example intended for ISO booting over HTTP
2. A more advanced example that chains to another network boot workflow
3. A Debian installer example enabled by default so the app is usable out of the box

For Windows PE booting, use the first example, replace the URL with your own ISO location, and remove the comment marker.
For more advanced scenarios, use the second example as a starting point and customize the script according to your environment.

## Local Development

The server implementation is written in plain Java and does not depend on Android APIs.
This makes it possible to run the server component on any system with a supported JDK.

Example:

```sh
gradlew :app:localRun --args="--server-ip=192.168.1.2 --http-root=D: --http-port=8080"
```

To stop the local server, type: `stop`


License
-------
Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
