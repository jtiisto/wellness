/**
 * NumericInput - A number input that handles decimal entry correctly.
 *
 * Standard controlled <input type="number"> with Number() parsing breaks
 * decimal entry: typing "57." gets parsed to 57 and re-rendered without
 * the period. This component maintains local string state while focused
 * and only pushes parsed numbers to the parent.
 */
import { h } from 'preact';
import { useState, useEffect, useRef } from 'preact/hooks';
import htm from 'htm';

const html = htm.bind(h);

export function NumericInput({ value, onValueChange, ...props }) {
    const [localValue, setLocalValue] = useState(value != null ? String(value) : '');
    const focused = useRef(false);

    // Sync external value changes (e.g. from sync) but not while editing
    useEffect(() => {
        if (!focused.current) {
            setLocalValue(value != null ? String(value) : '');
        }
    }, [value]);

    return html`<input
        type="number"
        ...${props}
        value=${localValue}
        onFocus=${() => { focused.current = true; }}
        onInput=${(e) => {
            setLocalValue(e.target.value);
            const num = e.target.value === '' ? null : Number(e.target.value);
            if (num === null || !isNaN(num)) {
                onValueChange(num);
            }
        }}
        onBlur=${(e) => {
            focused.current = false;
            const num = e.target.value === '' ? null : Number(e.target.value);
            if (num === null || !isNaN(num)) {
                onValueChange(num);
            }
            // Normalize display (strip trailing period/zeros)
            setLocalValue(num != null ? String(num) : '');
        }}
    />`;
}
