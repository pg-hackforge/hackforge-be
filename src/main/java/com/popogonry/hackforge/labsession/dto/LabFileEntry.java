package com.popogonry.hackforge.labsession.dto;

/** openapi.yaml `LabFileEntry` */
public record LabFileEntry(String path, long size, boolean readOnly, boolean openable, boolean modified) {
}
