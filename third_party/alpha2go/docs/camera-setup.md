# Camera setup (α6700)

Menu names follow Sony's [α6700 Help Guide](https://helpguide.sony.net/ilc/2320/v1/en/contents/0903_pc_remote_function.html).

## 1. Join your Wi-Fi

1. MENU → (Network) → **Wi-Fi** → **Access Point Set.**: pick your network and enter the password.
2. MENU → (Network) → **Wi-Fi Connect**: **On**.
3. Optional: give the camera a fixed IP, or a DHCP reservation on your router.

`discovery.Find` only searches the local layer-2 network. Across routers, use the IP directly.

## 2. Turn on PC Remote

MENU → (Network) → **Cnct./PC Remote** → **PC Remote Function** → **PC Remote**: **On**.

Don't connect a smartphone (Creators' App) at the same time. While a phone is connected, the camera does not accept a computer.

## 3. Access Authentication

With **Access Authentication** on (the default), the camera only accepts connections through its SSH server.

1. Open MENU → (Network) → **Network Option** → **Access Authen. Info**. It shows a **user**, a **password** and a **fingerprint** (`SHA256:…`).
2. Pass all three to `ptpip.SSHConfig`, or to ptpprobe:

   ```sh
   PTPPROBE_SSH_PASSWORD='<password>' ptpprobe -host <camera-ip> \
     -ssh-user <user> -ssh-fingerprint 'SHA256:<fingerprint>'
   ```

alpha2go checks the camera's host key against the fingerprint **before** it sends the password. A different SSH server on your network never sees the password. The fingerprint changes when you reset the camera's network settings.

Without Access Authentication the camera opens plain PTP/IP on port 15740 and asks for pairing on the first connection. This path has not been tested.

## 4. Check the connection

Run ptpprobe with only the connection flags. It prints the device info, the Sony handshake result and the advertised operations.

| Message | Likely cause |
|---|---|
| connection refused / timeout | camera off or asleep, Wi-Fi Connect off, PC Remote off, other network |
| `camera rejected the Access Authentication user/password` | wrong user or password |
| `fingerprint ... does not match` | wrong fingerprint, or the IP belongs to another device. The password was not sent. |
| handshake fails | a smartphone is connected, or the camera shows a prompt |
| `StoreNotAvailable` | standard PTP storage operations need the contents transfer mode (`-sony-xfer 2,1,0`), which locks the camera UI. The 0x923x content operations don't. |

## Power saving

The camera drops off the network when it powers down. A longer **Power Save Start Time** keeps it reachable for longer.
