/* Copyright (c) 2010-2012, Revori Contributors

   Permission to use, copy, modify, and/or distribute this software
   for any purpose with or without fee is hereby granted, provided
   that the above copyright notice and this permission notice appear
   in all copies. */

package com.readytalk.revori.server.protocol;

import java.io.IOException;

public interface Serializer<T> {
  public void writeTo(WriteContext context, T v) throws IOException;
}
