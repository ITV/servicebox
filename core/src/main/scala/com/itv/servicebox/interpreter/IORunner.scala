package com.itv.servicebox.interpreter

import cats.effect.IO
import com.itv.servicebox.algebra

class IORunner(ctrl: algebra.ServiceController[IO]) extends algebra.Runner[IO](ctrl)
