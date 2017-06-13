/* Input.java
 * Component: ProperJavaRDP
 *
 * Copyright (c) 2005 Propero Limited
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or (at
 * your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA 02111-1307
 * USA
 *
 * (See gpl.txt for details of the GNU General Public License.)
 *
 */

package net.propero.rdp;

import java.awt.KeyboardFocusManager;
import java.awt.Toolkit;
import java.awt.event.InputEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;
import java.awt.event.MouseWheelEvent;
import java.awt.event.MouseWheelListener;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import net.propero.rdp.keymapping.KeyCode;
import net.propero.rdp.keymapping.KeyCode_FileBased;
import net.propero.rdp.keymapping.KeyMapException;
import net.propero.rdp.ui.RdesktopCanvas;
import net.propero.rdp.ui.RdesktopFrame;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Handles input events and sends relevant input data to server
 */
public class Input {

	private static final Logger LOGGER = LogManager.getLogger();

	/**
	 * Flags supported within the input capset.
	 *
	 * @see [MS-RDPBCGR] 2.2.7.1.6
	 */
	public static enum InputCapsetFlag {
		/**
		 * Indicates support for using scancodes in the Keyboard Event
		 * notifications (sections 2.2.8.1.1.3.1.1.1 and 2.2.8.1.2.2.1).
		 * <p>
		 * This flag is required.
		 */
		SCANCODES(0x0001),
		// none for 0x0002
		/**
		 * Indicates support for Extended Mouse Event notifications (sections
		 * 2.2.8.1.1.3.1.1.4 and 2.2.8.1.2.2.4).
		 */
		MOUSEX(0x0004),
		/**
		 * Advertised by RDP 5.0 and 5.1 servers. RDP 5.2, 6.0, 6.1, 7.0, 7.1,
		 * 8.0, 8.1, 10.0, 10.1, and 10.2 servers advertise the
		 * INPUT_FLAG_FASTPATH_INPUT2 flag to indicate support for fast-path
		 * input.
		 */
		FASTPATH_INPUT(0x0008),
		/**
		 * Indicates support for Unicode Keyboard Event notifications (sections
		 * 2.2.8.1.1.3.1.1.2 and 2.2.8.1.2.2.2).
		 */
		UNICODE(0x0010),
		FASTPATH_INPUT2(0x0020),
		// UNUSED1(0x0040),
		// UNUSED2(0x0080),
		/**
		 * Indicates support for horizontal mouse wheel notifications (sections
		 * 2.2.8.1.1.3.1.1.3 and 2.2.8.1.2.2.3).
		 */
		MOUSE_HWHEEL(0x0100),
		/**
		 * Indicates support for Quality of Experience (QoE) timestamp
		 * notifications (section 2.2.8.1.2.2.6). There is no slow-path support
		 * for Quality of Experience (QoE) timestamps.
		 */
		QOE_TIMESTAMPS(0x0200);

		public final int flag;
		private InputCapsetFlag(int flag) {
			this.flag = flag;
		}
	}

	/**
	 * Types of values for the Input PDU.
	 *
	 * @see [MS-RDPBCGR] 2.2.8.1.1.3.1.1
	 */
	public static enum InputType {
		/** Indicates a Synchronize Event (section 2.2.8.1.1.3.1.1.5). */
		SYNC(0x0000),
		// Not only below, but there also was: RDP_INPUT_CODEPOINT = 1
		/**
		 * Indicates an Unused Event (section 2.2.8.1.1.3.1.1.6).
		 * <p>
		 * It appears that this originally indicated a VKEY, but that behavior
		 * is deprecated according to the spec, and this SHALL NOT be used.
		 * ProperJavaRDP originally had <code>RDP_INPUT_VIRTKEY = 2</code>,
		 * which is where I get this name.
		 */
		@Deprecated
		UNUSED(0x0002),
		/** Indicates a Keyboard Event (section 2.2.8.1.1.3.1.1.1). */
		SCANCODE(0x0004),
		/** Indicates a Unicode Keyboard Event (section 2.2.8.1.1.3.1.1.2). */
		UNICODE(0x0005),
		/** Indicates a Mouse Event (section 2.2.8.1.1.3.1.1.3). */
		MOUSE(0x8001),
		/** Indicates an Extended Mouse Event (section 2.2.8.1.1.3.1.1.4). */
		MOUSEX(0x8002);

		public final int id;
		private InputType(int id) {
			this.id = id;
		}
	}

	KeyCode_FileBased newKeyMapper = null;

	protected Set<Integer> pressedKeys;

	@Deprecated
	protected static boolean capsLockOn = false;

	@Deprecated
	protected static boolean numLockOn = false;

	@Deprecated
	protected static boolean scrollLockOn = false;

	@Deprecated
	protected static boolean serverAltDown = false;

	@Deprecated
	protected static boolean altDown = false;

	@Deprecated
	protected static boolean ctrlDown = false;

	@Deprecated
	protected static long last_mousemove = 0;

	// Using this flag value (0x0001) seems to do nothing, and after running
	// through other possible values, the RIGHT flag does not appear to be
	// implemented
	protected static final int KBD_FLAG_RIGHT = 0x0001;

	protected static final int KBD_FLAG_EXT = 0x0100;

	// QUIET flag is actually as below (not 0x1000 as in rdesktop)
	protected static final int KBD_FLAG_QUIET = 0x200;

	protected static final int KBD_FLAG_DOWN = 0x4000;

	protected static final int KBD_FLAG_UP = 0x8000;

	protected static final int RDP_KEYPRESS = 0;

	protected static final int RDP_KEYRELEASE = KBD_FLAG_DOWN | KBD_FLAG_UP;

	/**
	 * Indicates that the mouse position MUST be updated to the location
	 * specified by the xPos and yPos fields.
	 *
	 * @see [MS-RDPBCGR] 2.2.8.1.1.3.1.1.3
	 */
	protected static final int MOUSE_FLAG_MOVE = 0x0800;

	/**
	 * Indicates that a click event has occurred at the position specified by
	 * the xPos and yPos fields. The button flags indicate which button has been
	 * clicked and at least one of these flags MUST be set.
	 *
	 * @see [MS-RDPBCGR] 2.2.8.1.1.3.1.1.3
	 */
	private static final int MOUSE_FLAG_DOWN = 0x8000;
	/**
	 * Mouse button 1 (left button) was clicked or released. If the
	 * PTRFLAGS_DOWN flag is set, then the button was clicked, otherwise it was
	 * released.
	 *
	 * @see [MS-RDPBCGR] 2.2.8.1.1.3.1.1.3
	 */
	private static final int MOUSE_FLAG_BUTTON1 = 0x1000;
	/**
	 * Mouse button 2 (right button) was clicked or released. If the
	 * PTRFLAGS_DOWN flag is set, then the button was clicked, otherwise it was
	 * released.
	 *
	 * @see [MS-RDPBCGR] 2.2.8.1.1.3.1.1.3
	 */
	private static final int MOUSE_FLAG_BUTTON2 = 0x2000;
	/**
	 * Mouse button 3 (middle button or wheel) was clicked or released. If the
	 * PTRFLAGS_DOWN flag is set, then the button was clicked, otherwise it was
	 * released.
	 *
	 * @see [MS-RDPBCGR] 2.2.8.1.1.3.1.1.3
	 */
	private static final int MOUSE_FLAG_BUTTON3 = 0x4000;

	/**
	 * The event is a vertical mouse wheel rotation. The only valid flags in a
	 * vertical wheel rotation event are PTRFLAGS_WHEEL_NEGATIVE and the
	 * WheelRotationMask; all other pointer flags are ignored.
	 *
	 * @see [MS-RDPBCGR] 2.2.8.1.1.3.1.1.3
	 */
	private static final int MOUSE_FLAG_VWHEEL = 0x0200;
	/**
	 * The event is a horizontal mouse wheel rotation. The only valid flags in a
	 * horizontal wheel rotation event are PTRFLAGS_WHEEL_NEGATIVE and the
	 * WheelRotationMask; all other pointer flags are ignored. This flag MUST NOT
	 * be sent to a server that does not indicate support for horizontal mouse
	 * wheel events in the Input Capability Set (section 2.2.7.1.6).
	 *
	 * @see [MS-RDPBCGR] 2.2.8.1.1.3.1.1.3
	 */
	private static final int MOUSE_FLAG_HWHEEL = 0x0400;
	/**
	 * The wheel rotation value (contained in the WheelRotationMask bit field)
	 * is negative and MUST be sign-extended before injection at the server.
	 *
	 * @see [MS-RDPBCGR] 2.2.8.1.1.3.1.1.3
	 */
	private static final int MOUSE_FLAG_WHEEL_NEGATIVE = 0x0100;

	protected static int time = 0;

	public KeyEvent lastKeyEvent = null;

	public boolean modifiersValid = false;

	public boolean keyDownWindows = false;

	protected RdesktopCanvas canvas = null;

	protected Rdp rdp = null;

	protected final Options options;

	/**
	 * Create a new Input object with a given keymap object
	 *
	 * @param c
	 *            Canvas on which to listen for input events
	 * @param r
	 *            Rdp layer on which to send input messages
	 * @param k
	 *            Key map to use in handling keyboard events
	 */
	public Input(Options options, RdesktopCanvas c, Rdp r, KeyCode_FileBased k) {
		this.newKeyMapper = k;
		this.canvas = c;
		this.rdp = r;
		addInputListeners();
		this.pressedKeys = new HashSet<>();
		this.options = options;

		this.configureDefaultFocusTraversalKeys();
	}

	/**
	 * Create a new Input object, using a keymap generated from a specified file
	 *
	 * @param c
	 *            Canvas on which to listen for input events
	 * @param r
	 *            Rdp layer on which to send input messages
	 * @param keymapFile
	 *            Path to file containing keymap data
	 */
	public Input(Options options, RdesktopCanvas c, Rdp r, String keymapFile) {
		try {
			newKeyMapper = new KeyCode_FileBased(options, keymapFile);
		} catch (KeyMapException kmEx) {
			LOGGER.fatal("Failed to load keymaps!", kmEx);
			if (!options.noSystemExit) {
				System.exit(-1);
			}
		}

		this.canvas = c;
		this.rdp = r;
		addInputListeners();
		this.pressedKeys = new HashSet<>();
		this.options = options;

		this.configureDefaultFocusTraversalKeys();
	}

	/**
	 * Add all relevant input listeners to the canvas
	 */
	public void addInputListeners() {
		canvas.addMouseListener(new RdesktopMouseAdapter());
		canvas.addMouseMotionListener(new RdesktopMouseMotionAdapter());
		canvas.addKeyListener(new RdesktopKeyAdapter());
		canvas.addMouseWheelListener(new RdesktopMouseWheelAdapter());
	}

	/**
	 * Configures focus traversal keys, so that tab and shift-tab have no
	 * special meaning.
	 */
	private void configureDefaultFocusTraversalKeys() {
		KeyboardFocusManager.getCurrentKeyboardFocusManager()
		.setDefaultFocusTraversalKeys(
				KeyboardFocusManager.FORWARD_TRAVERSAL_KEYS,
				Collections.emptySet());
		KeyboardFocusManager.getCurrentKeyboardFocusManager()
		.setDefaultFocusTraversalKeys(
				KeyboardFocusManager.BACKWARD_TRAVERSAL_KEYS,
				Collections.emptySet());
	}

	/**
	 * Send a sequence of key actions to the server
	 *
	 * @param pressSequence
	 *            String representing a sequence of key actions. Actions are
	 *            represented as a pair of consecutive characters, the first
	 *            character's value (cast to integer) being the scancode to
	 *            send, the second (cast to integer) of the pair representing
	 *            the action (0 == UP, 1 == DOWN, 2 == QUIET UP, 3 == QUIET
	 *            DOWN).
	 */
	public void sendKeyPresses(String pressSequence) {
		try {
			String debugString = "Sending keypresses: ";
			for (int i = 0; i < pressSequence.length(); i += 2) {
				int scancode = pressSequence.charAt(i);
				int action = pressSequence.charAt(i + 1);
				int flags = 0;

				if (action == KeyCode_FileBased.UP) {
					flags = RDP_KEYRELEASE;
				} else if (action == KeyCode_FileBased.DOWN) {
					flags = RDP_KEYPRESS;
				} else if (action == KeyCode_FileBased.QUIETUP) {
					flags = RDP_KEYRELEASE | KBD_FLAG_QUIET;
				} else if (action == KeyCode_FileBased.QUIETDOWN) {
					flags = RDP_KEYPRESS | KBD_FLAG_QUIET;
				}

				long t = getTime();

				debugString += "(0x"
						+ Integer.toHexString(scancode)
						+ ", "
						+ ((action == KeyCode_FileBased.UP || action == KeyCode_FileBased.QUIETUP) ? "up"
								: "down")
								+ ((flags & KBD_FLAG_QUIET) != 0 ? " quiet" : "")
								+ " at " + t + ")";

				sendScancode(t, flags, scancode);
			}

			if (pressSequence.length() > 0) {
				LOGGER.debug(debugString);
			}
		} catch (Exception ex) {
			LOGGER.warn("Failed to send key press sequence " + pressSequence, ex);
			return;
		}
	}

	/**
	 * Retrieve the next "timestamp", by incrementing previous stamp (up to the
	 * maximum value of an integer, at which the timestamp is reverted to 1)
	 *
	 * @return New timestamp value
	 */
	public static int getTime() {
		time++;
		if (time == Integer.MAX_VALUE) {
			time = 1;
		}
		return time;
	}

	/**
	 * Handle loss of focus to the main canvas. Clears all depressed keys
	 * (sending release messages to the server.
	 */
	public void lostFocus() {
		clearKeys();
		modifiersValid = false;
	}

	/**
	 * Handle the main canvas gaining focus. Check locking key states.
	 */
	public void gainedFocus() {
		doLockKeys(); // ensure lock key states are correct
	}

	/**
	 * Send a keyboard event to the server
	 *
	 * @param time
	 *            Time stamp to identify this event
	 * @param flags
	 *            Flags defining the nature of the event (eg:
	 *            press/release/quiet/extended)
	 * @param scancode
	 *            Scancode value identifying the key in question
	 */
	public void sendScancode(long time, int flags, int scancode) {

		if (scancode == 0x38) { // be careful with alt
			if ((flags & RDP_KEYRELEASE) != 0) {
				// logger.info("Alt release, serverAltDown = " + serverAltDown);
				serverAltDown = false;
			}
			if ((flags == RDP_KEYPRESS)) {
				// logger.info("Alt press, serverAltDown = " + serverAltDown);
				serverAltDown = true;
			}
		}

		if ((scancode & KeyCode.SCANCODE_EXTENDED) != 0) {
			rdp.sendInput((int) time, InputType.SCANCODE,
					flags | KBD_FLAG_EXT, scancode & ~KeyCode.SCANCODE_EXTENDED, 0);
		} else {
			rdp.sendInput((int) time, InputType.SCANCODE, flags, scancode, 0);
		}
	}

	/**
	 * Release any modifier keys that may be depressed.
	 */
	public void clearKeys() {
		if (!modifiersValid) {
			return;
		}

		altDown = false;
		ctrlDown = false;

		if (lastKeyEvent == null) {
			return;
		}

		if (lastKeyEvent.isShiftDown())
		{
			sendScancode(getTime(), RDP_KEYRELEASE, 0x2a); // shift
		}
		if (lastKeyEvent.isAltDown() || serverAltDown) {
			sendScancode(getTime(), RDP_KEYRELEASE, 0x38); // ALT
			sendScancode(getTime(), RDP_KEYPRESS | KBD_FLAG_QUIET, 0x38); // ALT
			sendScancode(getTime(), RDP_KEYRELEASE | KBD_FLAG_QUIET, 0x38); // l.alt
		}
		if (lastKeyEvent.isControlDown()) {
			sendScancode(getTime(), RDP_KEYRELEASE, 0x1d); // l.ctrl
			// sendScancode(getTime(), RDP_KEYPRESS | KBD_FLAG_QUIET, 0x1d); //
			// Ctrl
			// sendScancode(getTime(), RDP_KEYRELEASE | KBD_FLAG_QUIET, 0x1d);
			// // ctrl
		}

		if (lastKeyEvent.isAltGraphDown()) {
			sendScancode(getTime(), RDP_KEYRELEASE,
					0x38 | KeyCode.SCANCODE_EXTENDED); // r.alt
		}
	}

	/**
	 * Send keypress events for any modifier keys that are currently down
	 */
	public void setKeys() {
		if (!modifiersValid) {
			return;
		}

		if (lastKeyEvent == null) {
			return;
		}

		if (lastKeyEvent.isShiftDown()) {
			sendScancode(getTime(), RDP_KEYPRESS, 0x2a); // shift
		}
		if (lastKeyEvent.isAltDown()) {
			sendScancode(getTime(), RDP_KEYPRESS, 0x38); // l.alt
		}
		if (lastKeyEvent.isControlDown()) {
			sendScancode(getTime(), RDP_KEYPRESS, 0x1d); // l.ctrl
		}
		if (lastKeyEvent.isAltGraphDown()) {
			sendScancode(getTime(), RDP_KEYPRESS,
					0x38 | KeyCode.SCANCODE_EXTENDED); // r.alt
		}
	}

	class RdesktopKeyAdapter extends KeyAdapter {

		/**
		 * Construct an RdesktopKeyAdapter based on the parent KeyAdapter class
		 */
		public RdesktopKeyAdapter() {
			super();
		}

		/**
		 * Handle a keyPressed event, sending any relevant keypresses to the
		 * server
		 */
		@Override
		public void keyPressed(KeyEvent e) {
			lastKeyEvent = e;
			modifiersValid = true;
			long time = getTime();

			// Some java versions have keys that don't generate keyPresses -
			// here we add the key so we can later check if it happened
			pressedKeys.add(new Integer(e.getKeyCode()));

			LOGGER.debug("PRESSED keychar='" + e.getKeyChar() + "' keycode=0x"
					+ Integer.toHexString(e.getKeyCode()) + " char='"
					+ ((char) e.getKeyCode()) + "'");

			if (rdp != null) {
				if (!handleSpecialKeys(time, e, true)) {
					sendKeyPresses(newKeyMapper.getKeyStrokes(e));
				}
				// sendScancode(time, RDP_KEYPRESS, keys.getScancode(e));
			}
		}

		/**
		 * Handle a keyTyped event, sending any relevant keypresses to the
		 * server
		 */
		@Override
		public void keyTyped(KeyEvent e) {
			lastKeyEvent = e;
			modifiersValid = true;
			long time = getTime();

			// Some java versions have keys that don't generate keyPresses -
			// here we add the key so we can later check if it happened
			pressedKeys.add(new Integer(e.getKeyCode()));

			LOGGER.debug("TYPED keychar='" + e.getKeyChar() + "' keycode=0x"
					+ Integer.toHexString(e.getKeyCode()) + " char='"
					+ ((char) e.getKeyCode()) + "'");

			if (rdp != null) {
				if (!handleSpecialKeys(time, e, true))
				{
					sendKeyPresses(newKeyMapper.getKeyStrokes(e));
					// sendScancode(time, RDP_KEYPRESS, keys.getScancode(e));
				}
			}
		}

		/**
		 * Handle a keyReleased event, sending any relevent key events to the
		 * server
		 */
		@Override
		public void keyReleased(KeyEvent e) {
			// Some java versions have keys that don't generate keyPresses -
			// we added the key to the vector in keyPressed so here we check for
			// it
			Integer keycode = new Integer(e.getKeyCode());
			if (!pressedKeys.contains(keycode)) {
				this.keyPressed(e);
			}

			pressedKeys.remove(keycode);

			lastKeyEvent = e;
			modifiersValid = true;
			long time = getTime();

			LOGGER.debug("RELEASED keychar='" + e.getKeyChar() + "' keycode=0x"
					+ Integer.toHexString(e.getKeyCode()) + " char='"
					+ ((char) e.getKeyCode()) + "'");
			if (rdp != null) {
				if (!handleSpecialKeys(time, e, false)) {
					sendKeyPresses(newKeyMapper.getKeyStrokes(e));
					// sendScancode(time, RDP_KEYRELEASE, keys.getScancode(e));
				}
			}
		}

	}

	/**
	 * Act on any keyboard shortcuts that a specified KeyEvent may describe
	 *
	 * @param time
	 *            Time stamp for event to send to server
	 * @param e
	 *            Keyboard event to be checked for shortcut keys
	 * @param pressed
	 *            True if key was pressed, false if released
	 * @return True if a shortcut key combination was detected and acted upon,
	 *         false otherwise
	 */
	public boolean handleShortcutKeys(long time, KeyEvent e, boolean pressed) {
		if (!e.isAltDown()) {
			return false;
		}

		if (!altDown)
		{
			return false; // all of the below have ALT on
		}

		switch (e.getKeyCode()) {

		/*
		 * case KeyEvent.VK_M: if(pressed) ((RdesktopFrame_Localised)
		 * canvas.getParent()).toggleMenu(); break;
		 */

		case KeyEvent.VK_ENTER:
			sendScancode(time, RDP_KEYRELEASE, 0x38);
			altDown = false;
			((RdesktopFrame) canvas.getParent()).toggleFullScreen();
			break;

			/*
			 * The below case block handles "real" ALT+TAB events. Once the TAB in
			 * an ALT+TAB combination has been pressed, the TAB is sent to the
			 * server with the quiet flag on, as is the subsequent ALT-up.
			 *
			 * This ensures that the initial ALT press is "undone" by the server.
			 *
			 * --- Tom Elliott, 7/04/05
			 */

		case KeyEvent.VK_TAB: // Alt+Tab received, quiet combination

			sendScancode(time, (pressed ? RDP_KEYPRESS : RDP_KEYRELEASE)
					| KBD_FLAG_QUIET, 0x0f);
			if (!pressed) {
				sendScancode(time, RDP_KEYRELEASE | KBD_FLAG_QUIET, 0x38); // Release
				// Alt
			}

			if (pressed) {
				LOGGER.debug("Alt + Tab pressed, ignoring, releasing tab");
			}
			break;
		case KeyEvent.VK_PAGE_UP: // Alt + PgUp = Alt-Tab
			sendScancode(time, pressed ? RDP_KEYPRESS : RDP_KEYRELEASE, 0x0f); // TAB
			if (pressed) {
				LOGGER.debug("shortcut pressed: sent ALT+TAB");
			}
			break;
		case KeyEvent.VK_PAGE_DOWN: // Alt + PgDown = Alt-Shift-Tab
			if (pressed) {
				sendScancode(time, RDP_KEYPRESS, 0x2a); // Shift
				sendScancode(time, RDP_KEYPRESS, 0x0f); // TAB
				LOGGER.debug("shortcut pressed: sent ALT+SHIFT+TAB");
			} else {
				sendScancode(time, RDP_KEYRELEASE, 0x0f); // TAB
				sendScancode(time, RDP_KEYRELEASE, 0x2a); // Shift
			}

			break;
		case KeyEvent.VK_INSERT: // Alt + Insert = Alt + Esc
			sendScancode(time, pressed ? RDP_KEYPRESS : RDP_KEYRELEASE, 0x01); // ESC
			if (pressed) {
				LOGGER.debug("shortcut pressed: sent ALT+ESC");
			}
			break;
		case KeyEvent.VK_HOME: // Alt + Home = Ctrl + Esc (Start)
			if (pressed) {
				sendScancode(time, RDP_KEYRELEASE, 0x38); // ALT
				sendScancode(time, RDP_KEYPRESS, 0x1d); // left Ctrl
				sendScancode(time, RDP_KEYPRESS, 0x01); // Esc
				LOGGER.debug("shortcut pressed: sent CTRL+ESC (Start)");

			} else {
				sendScancode(time, RDP_KEYRELEASE, 0x01); // escape
				sendScancode(time, RDP_KEYRELEASE, 0x1d); // left ctrl
				// sendScancode(time,RDP_KEYPRESS,0x38); // ALT
			}

			break;
		case KeyEvent.VK_END: // Ctrl+Alt+End = Ctrl+Alt+Del
			if (ctrlDown) {
				sendScancode(time, pressed ? RDP_KEYPRESS : RDP_KEYRELEASE,
						0x53 | KeyCode.SCANCODE_EXTENDED); // DEL
				if (pressed) {
					LOGGER.debug("shortcut pressed: sent CTRL+ALT+DEL");
				}
			}
			break;
		case KeyEvent.VK_DELETE: // Alt + Delete = Menu
			if (pressed) {
				sendScancode(time, RDP_KEYRELEASE, 0x38); // ALT
				// need to do another press and release to shift focus from
				// to/from menu bar
				sendScancode(time, RDP_KEYPRESS, 0x38); // ALT
				sendScancode(time, RDP_KEYRELEASE, 0x38); // ALT
				sendScancode(time, RDP_KEYPRESS,
						0x5d | KeyCode.SCANCODE_EXTENDED); // Menu
				LOGGER.debug("shortcut pressed: sent MENU");
			} else {
				sendScancode(time, RDP_KEYRELEASE,
						0x5d | KeyCode.SCANCODE_EXTENDED); // Menu
				// sendScancode(time,RDP_KEYPRESS,0x38); // ALT
			}
			break;
		case KeyEvent.VK_SUBTRACT: // Ctrl + Alt + Minus (on NUM KEYPAD) =
			// Alt+PrtSc
			if (ctrlDown) {
				if (pressed) {
					sendScancode(time, RDP_KEYRELEASE, 0x1d); // Ctrl
					sendScancode(time, RDP_KEYPRESS,
							0x37 | KeyCode.SCANCODE_EXTENDED); // PrtSc
					LOGGER.debug("shortcut pressed: sent ALT+PRTSC");
				} else {
					sendScancode(time, RDP_KEYRELEASE,
							0x37 | KeyCode.SCANCODE_EXTENDED); // PrtSc
					sendScancode(time, RDP_KEYPRESS, 0x1d); // Ctrl
				}
			}
			break;
		case KeyEvent.VK_ADD: // Ctrl + ALt + Plus (on NUM KEYPAD) = PrtSc
		case KeyEvent.VK_EQUALS: // for laptops that can't do Ctrl-Alt+Plus
			if (ctrlDown) {
				if (pressed) {
					sendScancode(time, RDP_KEYRELEASE, 0x38); // Alt
					sendScancode(time, RDP_KEYRELEASE, 0x1d); // Ctrl
					sendScancode(time, RDP_KEYPRESS,
							0x37 | KeyCode.SCANCODE_EXTENDED); // PrtSc
					LOGGER.debug("shortcut pressed: sent PRTSC");
				} else {
					sendScancode(time, RDP_KEYRELEASE,
							0x37 | KeyCode.SCANCODE_EXTENDED); // PrtSc
					sendScancode(time, RDP_KEYPRESS, 0x1d); // Ctrl
					sendScancode(time, RDP_KEYPRESS, 0x38); // Alt
				}
			}
			break;
		case KeyEvent.VK_MINUS: // for laptops that can't do Ctrl+Alt+Minus
			if (ctrlDown) {
				if (pressed) {
					sendScancode(time, RDP_KEYRELEASE, 0x1d); // Ctrl
					sendScancode(time, RDP_KEYPRESS,
							0x37 | KeyCode.SCANCODE_EXTENDED); // PrtSc
					LOGGER.debug("shortcut pressed: sent ALT+PRTSC");
				} else {
					sendScancode(time, RDP_KEYRELEASE,
							0x37 | KeyCode.SCANCODE_EXTENDED); // PrtSc
					sendScancode(time, RDP_KEYPRESS, 0x1d); // Ctrl
				}
			}
			break;
		default:
			return false;
		}
		return true;
	}

	/**
	 * Deal with modifier keys as control, alt or caps lock
	 *
	 * @param time
	 *            Time stamp for key event
	 * @param e
	 *            Key event to check for special keys
	 * @param pressed
	 *            True if key was pressed, false if released
	 * @return
	 */
	public boolean handleSpecialKeys(long time, KeyEvent e, boolean pressed) {
		if (handleShortcutKeys(time, e, pressed)) {
			return true;
		}

		switch (e.getKeyCode()) {
		case KeyEvent.VK_CONTROL:
			ctrlDown = pressed;
			return false;
		case KeyEvent.VK_ALT:
			altDown = pressed;
			return false;
		case KeyEvent.VK_CAPS_LOCK:
			if (pressed && options.caps_sends_up_and_down) {
				capsLockOn = !capsLockOn;
			}
			if (!options.caps_sends_up_and_down) {
				if (pressed) {
					capsLockOn = true;
				} else {
					capsLockOn = false;
				}
			}
			return false;
		case KeyEvent.VK_NUM_LOCK:
			if (pressed) {
				numLockOn = !numLockOn;
			}
			return false;
		case KeyEvent.VK_SCROLL_LOCK:
			if (pressed) {
				scrollLockOn = !scrollLockOn;
			}
			return false;
		case KeyEvent.VK_PAUSE: // untested
			// XXX This doesn't necessarilly match the spec (which mandates
			// that pause is handled in a wierd way)
			if (pressed) { // E1 1D 45 E1 9D C5
				rdp.sendInput((int) time, InputType.SCANCODE, RDP_KEYPRESS,
						0xe1, 0);
				rdp.sendInput((int) time, InputType.SCANCODE, RDP_KEYPRESS,
						0x1d, 0);
				rdp.sendInput((int) time, InputType.SCANCODE, RDP_KEYPRESS,
						0x45, 0);
				rdp.sendInput((int) time, InputType.SCANCODE, RDP_KEYPRESS,
						0xe1, 0);
				rdp.sendInput((int) time, InputType.SCANCODE, RDP_KEYPRESS,
						0x9d, 0);
				rdp.sendInput((int) time, InputType.SCANCODE, RDP_KEYPRESS,
						0xc5, 0);
			} else { // release left ctrl
				rdp.sendInput((int) time, InputType.SCANCODE, RDP_KEYRELEASE,
						0x1d, 0);
			}
			break;

			// Removed, as java on MacOS send the option key as VK_META
			/*
			 * case KeyEvent.VK_META: // Windows key logger.debug("Windows key
			 * received"); if(pressed){ sendScancode(time, RDP_KEYPRESS, 0x1d); //
			 * left ctrl sendScancode(time, RDP_KEYPRESS, 0x01); // escape } else{
			 * sendScancode(time, RDP_KEYRELEASE, 0x01); // escape
			 * sendScancode(time, RDP_KEYRELEASE, 0x1d); // left ctrl } break;
			 */

			// haven't found a way to detect BREAK key in java - VK_BREAK doesn't
			// exist
			/*
			 * case KeyEvent.VK_BREAK: if(pressed){
			 * sendScancode(time,RDP_KEYPRESS,(KeyCode.SCANCODE_EXTENDED | 0x46));
			 * sendScancode(time,RDP_KEYPRESS,(KeyCode.SCANCODE_EXTENDED | 0xc6)); } //
			 * do nothing on release break;
			 */
		default:
			return false; // not handled - use sendScancode instead
		}
		return true; // handled - no need to use sendScancode
	}

	/**
	 * Turn off any locking key, check states if available
	 */
	public void triggerReadyToSend() {
		capsLockOn = false;
		numLockOn = false;
		scrollLockOn = false;
		doLockKeys(); // ensure lock key states are correct
	}

	/**
	 * Previously:
	 * <blockquote>doesn't work on Java 1.4.1_02 or 1.4.2 on Linux, there is a bug in
	 * java....<br>
	 * does work on the same version on Windows.</blockquote>
	 * <p>I don't think this is an issue anymore, so I'm not testing the OS.
	 */
	protected void doLockKeys() {
		if (!options.useLockingKeyState) {
			return;
		}
		LOGGER.debug("doLockKeys");

		try {
			Toolkit tk = Toolkit.getDefaultToolkit();
			if (tk.getLockingKeyState(KeyEvent.VK_CAPS_LOCK) != capsLockOn) {
				capsLockOn = !capsLockOn;
				LOGGER.debug("CAPS LOCK toggle");
				sendScancode(getTime(), RDP_KEYPRESS, 0x3a);
				sendScancode(getTime(), RDP_KEYRELEASE, 0x3a);

			}
			if (tk.getLockingKeyState(KeyEvent.VK_NUM_LOCK) != numLockOn) {
				numLockOn = !numLockOn;
				LOGGER.debug("NUM LOCK toggle");
				sendScancode(getTime(), RDP_KEYPRESS, 0x45);
				sendScancode(getTime(), RDP_KEYRELEASE, 0x45);

			}
			if (tk.getLockingKeyState(KeyEvent.VK_SCROLL_LOCK) != scrollLockOn) {
				scrollLockOn = !scrollLockOn;
				LOGGER.debug("SCROLL LOCK toggle");
				sendScancode(getTime(), RDP_KEYPRESS, 0x46);
				sendScancode(getTime(), RDP_KEYRELEASE, 0x46);
			}
		} catch (Exception e) {
			options.useLockingKeyState = false;
			LOGGER.warn("Failed to handle key locking; disabling key locking!", e);
		}
	}

	/**
	 * Handle pressing of the middle mouse button, sending relevent event data
	 * to the server
	 *
	 * @param e
	 *            MouseEvent detailing circumstances under which middle button
	 *            was pressed
	 */
	protected void middleButtonPressed(MouseEvent e) {
		/*
		 * if (options.paste_hack && ctrlDown){ try{ canvas.setBusyCursor();
		 * }catch (RdesktopException ex){ logger.warn(ex.getMessage()); } if
		 * (capsLockOn){ logger.debug("Turning caps lock off for paste"); //
		 * turn caps lock off sendScancode(getTime(), RDP_KEYPRESS, 0x3a); //
		 * caps lock sendScancode(getTime(), RDP_KEYRELEASE, 0x3a); // caps lock }
		 * paste(); if (capsLockOn){ // turn caps lock back on
		 * logger.debug("Turning caps lock back on after paste");
		 * sendScancode(getTime(), RDP_KEYPRESS, 0x3a); // caps lock
		 * sendScancode(getTime(), RDP_KEYRELEASE, 0x3a); // caps lock }
		 * canvas.unsetBusyCursor(); } else
		 */
		rdp.sendInput(time, InputType.MOUSE, MOUSE_FLAG_BUTTON3
				| MOUSE_FLAG_DOWN, e.getX(), e.getY());
	}

	/**
	 * Handle release of the middle mouse button, sending relevent event data to
	 * the server
	 *
	 * @param e
	 *            MouseEvent detailing circumstances under which middle button
	 *            was released
	 */
	protected void middleButtonReleased(MouseEvent e) {
		/* if (!options.paste_hack || !ctrlDown) */
		rdp.sendInput(time, InputType.MOUSE, MOUSE_FLAG_BUTTON3, e.getX(), e
				.getY());
	}

	class RdesktopMouseAdapter extends MouseAdapter {
		@Override
		public void mousePressed(MouseEvent e) {
			if (e.getY() != 0) {
				((RdesktopFrame) canvas.getParent()).hideMenu();
			}

			int time = getTime();
			if (rdp != null) {
				if ((e.getModifiers() & InputEvent.BUTTON1_MASK) == InputEvent.BUTTON1_MASK) {
					LOGGER.debug("Mouse Button 1 Pressed.");
					rdp.sendInput(time, InputType.MOUSE, MOUSE_FLAG_BUTTON1
							| MOUSE_FLAG_DOWN, e.getX(), e.getY());
				} else if ((e.getModifiers() & InputEvent.BUTTON3_MASK) == InputEvent.BUTTON3_MASK) {
					LOGGER.debug("Mouse Button 3 Pressed.");
					rdp.sendInput(time, InputType.MOUSE, MOUSE_FLAG_BUTTON2
							| MOUSE_FLAG_DOWN, e.getX(), e.getY());
				} else if ((e.getModifiers() & InputEvent.BUTTON2_MASK) == InputEvent.BUTTON2_MASK) {
					LOGGER.debug("Middle Mouse Button Pressed.");
					middleButtonPressed(e);
				}
			}
		}

		@Override
		public void mouseReleased(MouseEvent e) {
			int time = getTime();
			if (rdp != null) {
				if ((e.getModifiers() & InputEvent.BUTTON1_MASK) == InputEvent.BUTTON1_MASK) {
					rdp.sendInput(time, InputType.MOUSE, MOUSE_FLAG_BUTTON1, e
							.getX(), e.getY());
				} else if ((e.getModifiers() & InputEvent.BUTTON3_MASK) == InputEvent.BUTTON3_MASK) {
					rdp.sendInput(time, InputType.MOUSE, MOUSE_FLAG_BUTTON2, e
							.getX(), e.getY());
				} else if ((e.getModifiers() & InputEvent.BUTTON2_MASK) == InputEvent.BUTTON2_MASK) {
					middleButtonReleased(e);
				}
			}
		}
	}

	class RdesktopMouseMotionAdapter extends MouseMotionAdapter {
		@Override
		public void mouseMoved(MouseEvent e) {
			int time = getTime();

			// Code to limit mouse events to 4 per second. Doesn't seem to
			// affect performance
			// long mTime = System.currentTimeMillis();
			// if((mTime - Input.last_mousemove) < 250) return;
			// Input.last_mousemove = mTime;

			// if(logger.isInfoEnabled()) logger.info("mouseMoved to
			// "+e.getX()+", "+e.getY()+" at "+time);

			// TODO: complete menu show/hide section
			if (e.getY() == 0)
			{
				((RdesktopFrame) canvas.getParent()).showMenu();
				// else ((RdesktopFrame_Localised) canvas.getParent()).hideMenu();
			}

			if (rdp != null) {
				rdp.sendInput(time, InputType.MOUSE, MOUSE_FLAG_MOVE, e.getX(),
						e.getY());
			}
		}

		@Override
		public void mouseDragged(MouseEvent e) {
			int time = getTime();
			// if(logger.isInfoEnabled()) logger.info("mouseMoved to
			// "+e.getX()+", "+e.getY()+" at "+time);
			if (rdp != null) {
				rdp.sendInput(time, InputType.MOUSE, MOUSE_FLAG_MOVE, e.getX(),
						e.getY());
			}
		}
	}

	private class RdesktopMouseWheelAdapter implements MouseWheelListener {
		@Override
		public void mouseWheelMoved(MouseWheelEvent e) {
			scroll(e);
		}
	}

	private static final int SCROLL_DEFAULT_SIZE = 0x80;
	private void scroll(MouseWheelEvent e) {
		// Right now, we only use a fixed size for scroll.
		if (e.getWheelRotation() < 0) { // negative values are up
			// ... which is sent as a positive value.
			scrollVertically(+SCROLL_DEFAULT_SIZE);
		} else {
			// and positive values are down, which is sent as a negative value.
			scrollVertically(-SCROLL_DEFAULT_SIZE);
		}
	}

	/**
	 * Sends a vertical scroll to the server.
	 * 
	 * @param by
	 *            The amount to scroll by, in some magic unit that is not
	 *            documented. Currently, negative values mean scrolling down,
	 *            and positive values up (which differs from MouseWheelEvent).
	 *            Must be 0-255.
	 */
	public void scrollVertically(int by) {
		int byAbs = Math.abs(by);
		if (byAbs > 255) {
			throw new IllegalArgumentException("Cannot scroll by more than 255: " + by);
		}
		if (by < 0) {
			rdp.sendInput(getTime(), InputType.MOUSE, MOUSE_FLAG_VWHEEL
					| MOUSE_FLAG_WHEEL_NEGATIVE | byAbs, 0, 0);
		} else {
			rdp.sendInput(getTime(), InputType.MOUSE,
					MOUSE_FLAG_VWHEEL | byAbs, 0, 0);
		}
	}

	/**
	 * Sends a horizontal scroll to the server. Note that Java does not appear
	 * to have any way to actually detect such scrolls.
	 * 
	 * @param by
	 *            The amount to scroll by, in some magic unit that is not
	 *            documented.  Must be 0-255.
	 * @throws UnsupportedOperationException
	 *             if the server does not support horizontal scrolls
	 */
	public void scrollHorizontally(boolean down, int by) {
		if (!options.supportedInputFlags.contains(InputCapsetFlag.MOUSE_HWHEEL)) {
			throw new UnsupportedOperationException(
					"Cannot scroll horizontally; server doesn't support it");
		}
		int byAbs = Math.abs(by);
		if (byAbs > 255) {
			throw new IllegalArgumentException("Cannot scroll by more than 255: " + by);
		}
		// Note: the coordinates are ignored for the wheel, so we leave them at 0
		if (by < 0) {
			rdp.sendInput(getTime(), InputType.MOUSE, MOUSE_FLAG_HWHEEL
					| MOUSE_FLAG_WHEEL_NEGATIVE | byAbs, 0, 0);
		} else {
			rdp.sendInput(getTime(), InputType.MOUSE,
					MOUSE_FLAG_HWHEEL | byAbs, 0, 0);
		}
	}
}
