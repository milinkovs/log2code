package org.log2code.analyzer.cli;

import java.util.Locale;
import picocli.CommandLine.ITypeConverter;
import picocli.CommandLine.TypeConversionException;

/** Accepts the lowercase {@code --out} values from 0.11/T07 ({@code opensearch|json|both}). */
public final class OutputModeConverter implements ITypeConverter<OutputMode> {

    @Override
    public OutputMode convert(String value) {
        try {
            return OutputMode.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new TypeConversionException("invalid --out value '" + value + "': expected one of opensearch, json, both");
        }
    }
}
