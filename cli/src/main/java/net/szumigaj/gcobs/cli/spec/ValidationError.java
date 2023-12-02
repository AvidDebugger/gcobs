package net.szumigaj.gcobs.cli.spec;

public record ValidationError(String field, String message, String hint) {

    public String format() {
        var sb = new StringBuilder();
        sb.append("ERROR: ").append(message);
        if (field != null && !field.isEmpty()) {
            sb.append("\n  field: ").append(field);
        }
        if (hint != null && !hint.isEmpty()) {
            sb.append("\n  hint: ").append(hint);
        }
        return sb.toString();
    }
}
