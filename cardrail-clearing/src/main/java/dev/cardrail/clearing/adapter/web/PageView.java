package dev.cardrail.clearing.adapter.web;

import java.util.List;

/** Same envelope as the switch's search endpoint. */
record PageView<T>(List<T> items, int page, int size, long total) {
}
