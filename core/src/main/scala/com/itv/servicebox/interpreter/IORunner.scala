package com.itv.servicebox.interpreter

import cats.effect.IO
import com.itv.servicebox.algebra

class IORunner(ctrl: algebra.Controller[IO]) extends algebra.Runner[IO](ctrl)
