# Enable USB CDC Data port for serial communication with Android
# Save this as boot.py on the CIRCUITPY drive
# This runs BEFORE code.py and configures USB

import usb_cdc

# Enable both console (for REPL/debugging) and data serial port
# The data port will be used for communication with Android
usb_cdc.enable(console=True, data=True)
