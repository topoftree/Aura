#!/usr/bin/env python3
"""Real SDL + loopback protocol tests on a Linux host, NOT Android/game tests.

AURA_SDL_TEST_LIBRARY=/path/to/host/libSDL2.so python3 tools/arm64ec/test_sdl_gamepad.py -v
"""
import ctypes as C
import os
import socket
import struct
import time
import unittest


class SdlGamepadTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.sdl = C.CDLL(os.environ['AURA_SDL_TEST_LIBRARY'])
        sdl = cls.sdl
        for name, args, result in [
            ('SDL_Init', [C.c_uint32], C.c_int), ('SDL_Quit', [], None),
            ('SDL_PumpEvents', [], None), ('SDL_PollEvent', [C.c_void_p], C.c_int),
            ('SDL_NumJoysticks', [], C.c_int), ('SDL_IsGameController', [C.c_int], C.c_int),
            ('SDL_GameControllerOpen', [C.c_int], C.c_void_p),
            ('SDL_GameControllerClose', [C.c_void_p], None),
            ('SDL_GameControllerGetJoystick', [C.c_void_p], C.c_void_p),
            ('SDL_GameControllerGetButton', [C.c_void_p, C.c_int], C.c_uint8),
            ('SDL_GameControllerGetAxis', [C.c_void_p, C.c_int], C.c_int16),
            ('SDL_JoystickInstanceID', [C.c_void_p], C.c_int32),
            ('SDL_GameControllerRumble', [C.c_void_p, C.c_uint16, C.c_uint16, C.c_uint32], C.c_int),
            ('SDL_GetError', [], C.c_char_p),
        ]:
            fn = getattr(sdl, name); fn.argtypes = args; fn.restype = result

    def setUp(self):
        self.host = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self.host.bind(('127.0.0.1', 7947)); self.host.settimeout(1)
        self.assertEqual(0, self.sdl.SDL_Init(0x3000), self.sdl.SDL_GetError())
        data, self.peer = self.host.recvfrom(256)
        self.assertEqual(b'\x08', data)
        self.controllers = []

    def tearDown(self):
        for controller in self.controllers: self.sdl.SDL_GameControllerClose(controller)
        self.sdl.SDL_Quit()
        self.host.close()

    def pump(self):
        result = []
        event = C.create_string_buffer(64)
        # Detect runs during the event pump and may publish an added device afterward.
        for _ in range(2):
            self.sdl.SDL_PumpEvents()
            while self.sdl.SDL_PollEvent(event): result.append(bytes(event))
        return result

    def discover(self, present=True, slot=0, axis_mode=1):
        packet = bytearray(256); packet[0] = 8
        if present:
            name = b'Backbone Labs, Inc. Backbone One'
            struct.pack_into('<BBBBHHB', packet, 1 + slot * 60,
                             1, 10 if axis_mode else 12, axis_mode, 1, 0x045e, 0x028e, len(name))
            packet[10 + slot * 60:10 + slot * 60 + len(name)] = name
        self.host.sendto(packet, self.peer)
        self.pump()

    def open_controller(self):
        self.assertEqual(1, self.sdl.SDL_NumJoysticks())
        self.assertTrue(self.sdl.SDL_IsGameController(0))
        controller = self.sdl.SDL_GameControllerOpen(0)
        self.assertTrue(controller, self.sdl.SDL_GetError())
        self.controllers.append(controller)
        self.pump()
        return controller

    def state(self, buttons=0, hat=255, axes=(0, 0, 0, 0, 0, 0), slot=0):
        packet = struct.pack('<BBHB' + 'h' * len(axes), 9, slot, buttons, hat, *axes)
        self.host.sendto(packet, self.peer)

    def test_start_unplugged_then_connect_and_reconnect(self):
        self.assertEqual(0, self.sdl.SDL_NumJoysticks())
        self.discover()
        first = self.open_controller()
        first_id = self.sdl.SDL_JoystickInstanceID(self.sdl.SDL_GameControllerGetJoystick(first))
        self.state(1); self.pump()
        self.assertEqual(1, self.sdl.SDL_GameControllerGetButton(first, 0))
        self.discover(False)
        self.assertEqual(0, self.sdl.SDL_NumJoysticks())
        self.assertEqual(0, self.sdl.SDL_GameControllerGetButton(first, 0))
        self.discover()
        second = self.open_controller()
        self.assertNotEqual(first_id, self.sdl.SDL_JoystickInstanceID(self.sdl.SDL_GameControllerGetJoystick(second)))
        self.state(1 << 7); self.pump()
        self.assertEqual(1, self.sdl.SDL_GameControllerGetButton(second, 6))  # Start

    def test_button_edges_are_preserved_and_axes_hat_triggers_map(self):
        self.discover(); controller = self.open_controller()
        self.state(1); self.state(0)
        events = self.pump()
        buttons = [(struct.unpack_from('<I', e)[0], e[12]) for e in events
                   if struct.unpack_from('<I', e)[0] in (0x651, 0x652)]
        self.assertIn((0x651, 0), buttons); self.assertIn((0x652, 0), buttons)
        self.state(0, 1, (32767, -32768, 16000, -12000, 32767, 0)); self.pump()
        self.assertEqual([32767, -32768, 16000, -12000, 32767, 0],
                         [self.sdl.SDL_GameControllerGetAxis(controller, i) for i in range(6)])
        self.assertEqual(1, self.sdl.SDL_GameControllerGetButton(controller, 11))
        self.assertEqual(1, self.sdl.SDL_GameControllerGetButton(controller, 14))

    def test_standard_directinput_mode_and_nonzero_player_slot(self):
        self.discover(slot=2, axis_mode=0); controller = self.open_controller()
        self.state(1 << 10, axes=(0, 0, 0, 0), slot=2); self.pump()
        self.assertEqual(32767, self.sdl.SDL_GameControllerGetAxis(controller, 4))
        self.assertEqual(0, self.sdl.SDL_GameControllerGetAxis(controller, 5))

    def test_malformed_packets_and_unrelated_udp_sender_are_ignored(self):
        self.discover(); controller = self.open_controller()
        for packet in (b'\x08', b'\x09\0', bytes([8]) + bytes(255) + b'extra',
                       struct.pack('<BBHB6h', 9, 4, 1, 255, 0, 0, 0, 0, 0, 0)):
            self.host.sendto(packet, self.peer)
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as outsider:
            outsider.sendto(struct.pack('<BBHB6h', 9, 0, 1, 255, 0, 0, 0, 0, 0, 0), self.peer)
        self.pump()
        self.assertEqual(1, self.sdl.SDL_NumJoysticks())
        self.assertEqual(0, self.sdl.SDL_GameControllerGetButton(controller, 0))

    def test_polling_continues_without_input_and_rumble_uses_same_channel(self):
        self.discover(); controller = self.open_controller()
        self.assertEqual(0, self.sdl.SDL_GameControllerRumble(controller, 1234, 5678, 100))
        packet, _ = self.host.recvfrom(256)
        self.assertEqual((19, 0, 1234, 5678, 5000), struct.unpack('<BBIII', packet))
        time.sleep(.27); self.pump()
        received = []
        self.host.settimeout(.1)
        try:
            while True: received.append(self.host.recvfrom(256)[0])
        except socket.timeout: pass
        self.assertIn(b'\x08', received)
        self.assertTrue(any(p[:2] == b'\x13\0' and struct.unpack_from('<II', p, 2) == (0, 0)
                            for p in received))

    def test_host_disappearing_releases_a_held_button(self):
        self.discover(); controller = self.open_controller()
        self.state(1); self.pump()
        self.assertEqual(1, self.sdl.SDL_GameControllerGetButton(controller, 0))
        time.sleep(3.05); self.pump()
        self.assertEqual(0, self.sdl.SDL_NumJoysticks())
        self.assertEqual(0, self.sdl.SDL_GameControllerGetButton(controller, 0))


if __name__ == '__main__': unittest.main()
