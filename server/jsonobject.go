package main

import (
	"bytes"
	"encoding/json"
	"errors"
)

// jsonObject is a JSON object that keeps its keys in their original order, so
// rewriting a hand-edited config changes only what we touch, not its layout.
type jsonObject []jsonField

type jsonField struct {
	Key   string
	Value json.RawMessage
}

func (o *jsonObject) UnmarshalJSON(data []byte) error {
	dec := json.NewDecoder(bytes.NewReader(data))
	if t, err := dec.Token(); err != nil {
		return err
	} else if t != json.Delim('{') {
		return errors.New("expected a JSON object")
	}
	fields := jsonObject{}
	for dec.More() {
		t, err := dec.Token()
		if err != nil {
			return err
		}
		var v json.RawMessage
		if err := dec.Decode(&v); err != nil {
			return err
		}
		fields = append(fields, jsonField{Key: t.(string), Value: v})
	}
	if _, err := dec.Token(); err != nil {
		return err
	}
	*o = fields
	return nil
}

func (o jsonObject) MarshalJSON() ([]byte, error) {
	var b bytes.Buffer
	b.WriteByte('{')
	for i, f := range o {
		if i > 0 {
			b.WriteByte(',')
		}
		key, err := json.Marshal(f.Key)
		if err != nil {
			return nil, err
		}
		b.Write(key)
		b.WriteByte(':')
		b.Write(f.Value)
	}
	b.WriteByte('}')
	return b.Bytes(), nil
}

// get returns the raw value of key, or nil if there is none.
func (o jsonObject) get(key string) json.RawMessage {
	for _, f := range o {
		if f.Key == key {
			return f.Value
		}
	}
	return nil
}

// set replaces key's value in place, or appends the key if it is new.
func (o *jsonObject) set(key string, v any) error {
	raw, err := json.Marshal(v)
	if err != nil {
		return err
	}
	for i := range *o {
		if (*o)[i].Key == key {
			(*o)[i].Value = raw
			return nil
		}
	}
	*o = append(*o, jsonField{Key: key, Value: raw})
	return nil
}
