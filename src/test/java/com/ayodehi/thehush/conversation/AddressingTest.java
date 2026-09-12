package com.ayodehi.thehush.conversation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AddressingTest {
    @Test
    void matchesFirstNameLastNameAndMisspellings() {
        assertTrue(Addressing.addressedByName("Bartholomew, where is the mansion?", "Bartholomew Quill"));
        assertTrue(Addressing.addressedByName("hey quill", "Bartholomew Quill"));
        assertTrue(Addressing.addressedByName("Bartholemew where are we", "Bartholomew Quill"));
        assertTrue(Addressing.addressedByName("traveler, come with me", "The Traveller"));
    }

    @Test
    void ignoresTheAndLateMentions() {
        assertFalse(Addressing.addressedByName("the mansion is far", "The Traveller"));
        assertFalse(Addressing.addressedByName("I wonder what the books say about a quill", "Bartholomew Quill"));
        assertFalse(Addressing.addressedByName("where is the nearest village", "The Traveller"));
    }
}
